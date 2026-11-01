package com.casa.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs while the TV is on: picks up notes (tv_sync every 3 s) and shows them over whatever
 * is playing, and runs the phone's remote-control commands (tv_remote, fast while in use).
 */
class CasaService : Service() {

    companion object {
        @Volatile var lastSyncOk = 0L
        @Volatile var lastNote: Note? = null
        @Volatile private var pending: Note? = null
        @Volatile private var instance: CasaService? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, CasaService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun takePending(): Note? = pending.also { pending = null }

        /** Reply from the overlay or NoteActivity; null marks the note seen without a reply. */
        fun answer(ctx: Context, note: Note, reply: String?) {
            val key = Prefs(ctx).tvKey ?: return
            Thread {
                try {
                    Api.rpc("tv_answer", JSONObject().put("p_tv_key", key).put("p_id", note.id).put("p_reply", reply ?: ""))
                } catch (_: Throwable) {}
            }.start()
            instance?.main?.post { instance?.showNext() }
        }

        fun replay(ctx: Context) {
            val n = lastNote ?: return
            instance?.main?.post { instance?.enqueue(n, sound = false) }
        }

        /** Asked from the setup screen: connect ADB now (the TV shows its prompt). */
        fun setupRemote(ctx: Context, done: (Boolean) -> Unit) {
            Thread {
                val ok = Remote.connect(ctx.applicationContext)
                instance?.capsDirty = true
                Handler(Looper.getMainLooper()).post { done(ok) }
            }.start()
        }
    }

    val main = Handler(Looper.getMainLooper())
    private val sync = Executors.newSingleThreadScheduledExecutor()
    private val remote = Executors.newSingleThreadScheduledExecutor()
    private lateinit var prefs: Prefs

    private val queue = ArrayDeque<Note>()
    private var overlay: View? = null
    private val shown = LinkedHashSet<String>()

    @Volatile private var lastCmdAt = 0L
    @Volatile var capsDirty = true
    private var lastCapsAt = 0L
    private var lastAppsJson: String? = null
    private var lastRemoteTry = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        goForeground()
        sync.scheduleWithFixedDelay({ safe { syncOnce() } }, 0, 3, TimeUnit.SECONDS)
        remote.execute { remoteLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        sync.shutdownNow()
        remote.shutdownNow()
        removeOverlay()
        super.onDestroy()
    }

    private inline fun safe(f: () -> Unit) = try { f() } catch (_: Throwable) {}

    // Notes ---------------------------------------------------------------------------------

    private fun syncOnce() {
        val key = prefs.tvKey ?: return
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val body = Api.rpc("tv_sync", JSONObject()
            .put("p_tv_key", key)
            .put("p_screen_on", screenOn)
            .put("p_device_id", prefs.deviceId)
            .put("p_name", prefs.name ?: JSONObject.NULL))
        lastSyncOk = System.currentTimeMillis()
        val arr = JSONArray(body)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val png = Base64.decode(o.optString("image_b64"), Base64.DEFAULT)
            val note = Note(o.getString("id"), o.optString("sender"), png)
            main.post { enqueue(note, sound = true) }
        }
    }

    private fun enqueue(note: Note, sound: Boolean) {
        if (sound && !shown.add(note.id)) return
        if (shown.size > 200) shown.remove(shown.first())
        queue.addLast(note)
        if (sound) chime()
        if (overlay == null) showNext()
    }

    fun showNext() {
        removeOverlay()
        val note = queue.removeFirstOrNull() ?: return
        lastNote = note
        if (canOverlay()) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                       else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            val v = NoteView.build(this, note) { reply -> answer(this, note, reply) }
            try {
                wm.addView(v, lp)
                overlay = v
                return
            } catch (_: Throwable) {}
        }
        // No overlay: open the note screen (works while our app is in front), and leave a notification.
        pending = note
        try {
            startActivity(Intent(this, NoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {}
        notifyNote(note)
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) {}
    }

    private fun canOverlay() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun chime() {
        try {
            MediaPlayer.create(this, R.raw.chime)?.apply {
                setOnCompletionListener { it.release() }
                start()
            }
        } catch (_: Throwable) {}
    }

    // Remote control ------------------------------------------------------------------------

    private fun remoteLoop() {
        while (!Thread.currentThread().isInterrupted) {
            safe { remoteOnce() }
            val busy = System.currentTimeMillis() - lastCmdAt < 60_000
            try { Thread.sleep(if (busy) 350 else 2000) } catch (_: InterruptedException) { return }
        }
    }

    private fun remoteOnce() {
        val key = prefs.tvKey ?: return
        val now = System.currentTimeMillis()
        // Reconnect on our own only once the TV has allowed us; never pop the prompt unasked.
        if (!Remote.ready && prefs.remoteOk && now - lastRemoteTry > 60_000) {
            lastRemoteTry = now
            if (Remote.connect(this)) capsDirty = true
        }
        val args = JSONObject().put("p_tv_key", key).put("p_device_id", prefs.deviceId)
        if (capsDirty || now - lastCapsAt > 5 * 60_000) {
            args.put("p_caps", JSONArray().apply { if (Remote.ready) put("remote") })
            val apps = appsJson()
            if (apps != lastAppsJson) args.put("p_apps", JSONArray(apps))
            lastAppsJson = apps
            lastCapsAt = now
            capsDirty = false
        }
        val cmds = JSONArray(Api.rpc("tv_remote", args))
        for (i in 0 until cmds.length()) {
            val c = cmds.getJSONObject(i)
            lastCmdAt = System.currentTimeMillis()
            run(c.optString("cmd"), c.optString("arg"))
        }
    }

    private fun run(cmd: String, arg: String) {
        when (cmd) {
            "key" -> if (!Remote.key(this, arg) && arg == "home") {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "sleep" -> Remote.key(this, "sleep")
            "wake" -> Remote.key(this, "wake")
            "launch" -> launch(arg)
            // "ping" only wakes up the fast polling above.
        }
    }

    private fun launch(pkg: String) {
        val pm = packageManager
        val leanback = Build.VERSION.SDK_INT >= 21 && pm.getLeanbackLaunchIntentForPackage(pkg) != null
        if (Remote.ready) {
            val cat = if (leanback) "android.intent.category.LEANBACK_LAUNCHER" else "android.intent.category.LAUNCHER"
            if (Remote.shell(this, "monkey -p $pkg -c $cat 1")) return
        }
        val i = (if (leanback) pm.getLeanbackLaunchIntentForPackage(pkg) else null) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
    }

    private fun appsJson(): String {
        val pm = packageManager
        val found = LinkedHashMap<String, String>()
        for (cat in listOf("android.intent.category.LEANBACK_LAUNCHER", Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (r in pm.queryIntentActivities(q, 0)) {
                val p = r.activityInfo.packageName
                if (p != packageName && p !in found) found[p] = r.loadLabel(pm).toString()
            }
        }
        val arr = JSONArray()
        found.entries.sortedBy { it.value.lowercase() }.take(60).forEach {
            arr.put(JSONObject().put("label", it.value).put("pkg", it.key))
        }
        return arr.toString()
    }

    // Foreground notification -----------------------------------------------------------------

    private fun channel(id: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }

    private fun builder(channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
        else @Suppress("DEPRECATION") Notification.Builder(this)

    private fun goForeground() {
        channel("casa", "Casa TV", NotificationManager.IMPORTANCE_MIN)
        val n = builder("casa")
            .setContentTitle("Casa TV")
            .setContentText("Lista para recibir notas")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun notifyNote(note: Note) {
        channel("casa_notes", "Notas de Casa TV", NotificationManager.IMPORTANCE_HIGH)
        val n = builder("casa_notes")
            .setContentTitle(if (note.sender.isBlank()) "Nueva nota" else "Nota de ${note.sender}")
            .setContentText("Abre Casa TV para verla")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 1,
                Intent(this, NoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2, n) } catch (_: Throwable) {}
    }
}
