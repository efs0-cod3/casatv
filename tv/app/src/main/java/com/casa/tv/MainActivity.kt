package com.casa.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Pairing on first run; afterwards the "send me a note" QR and the setup checklist. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var resumed = false
    private var statusView: TextView? = null
    private var remoteView: TextView? = null
    private var overlayView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.paired) CasaService.start(this)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (prefs.paired) showHome() else showPairing()
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun screen(title: String, subtitle: String, qr: String?, build: LinearLayout.() -> Unit) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), dp(36), dp(56), dp(36))
            setBackgroundColor(Ui.BG)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Ui.text(this, "CASA TV", 14f, Ui.DIM, bold = true).apply { letterSpacing = 0.2f })
        left.addView(Ui.text(this, title, 40f, Ui.TEXT, bold = true))
        left.addView(Ui.text(this, subtitle, 20f, Ui.DIM).apply { setPadding(0, dp(8), 0, dp(20)) })
        left.build()
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (qr != null) {
            val size = dp(260)
            val card = FrameLayout(this).apply {
                background = Ui.round(this@MainActivity, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 24)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            card.addView(ImageView(this).apply { setImageBitmap(Qr.bitmap(qr, 600)) }, FrameLayout.LayoutParams(size, size))
            root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(40)
            })
        }
        setContentView(root)
    }

    private fun LinearLayout.buttons(vararg items: Pair<String, () -> Unit>): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, (label, f) ->
            row.addView(Ui.button(context, label, 17f, f), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(12) })
        }
        addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        return row.getChildAt(0)
    }

    // Pairing -------------------------------------------------------------------------------

    private fun showPairing() {
        val code = prefs.pairCode
        val url = "${Config.SITE_URL}/#pair=$code"
        var msg: TextView? = null
        screen("Vincula esta TV",
            "Escanea el código con el móvil donde ya usas Casa TV y ponle nombre a esta TV.", url) {
            addView(Ui.text(context, "1.  Abre la cámara del móvil y apunta al código.\n2.  Escribe el nombre (por ejemplo «Habitación»).\n3.  Toca «Vincular».", 18f, Ui.TEXT).apply {
                setLineSpacing(0f, 1.35f)
            })
            val m = Ui.text(context, "Preparando…", 16f, Ui.DIM).apply { setPadding(0, dp(18), 0, 0) }
            addView(m)
            msg = m
        }
        io.execute {
            val ok = try {
                Api.rpc("tv_pair_start", JSONObject().put("p_code", code).put("p_device_id", prefs.deviceId)); true
            } catch (_: Throwable) { false }
            main.post { msg?.text = if (ok) "Esperando al móvil…" else "Sin conexión a internet. Reintentando…" }
            main.postDelayed({ if (resumed && !prefs.paired) pollPairing(msg) }, 2000)
        }
    }

    private fun pollPairing(msg: TextView?) {
        io.execute {
            val r = try {
                Api.obj("tv_pair_claim", JSONObject().put("p_code", prefs.pairCode))
            } catch (_: Throwable) { null }
            if (r != null && r.optBoolean("paired")) {
                prefs.tvKey = r.getString("tv_key")
                prefs.name = r.optString("name")
                loadInfo()
                main.post {
                    CasaService.start(this)
                    if (resumed) showHome()
                }
            } else {
                if (r == null) {   // maybe the code expired or we were offline: register it again
                    try { Api.rpc("tv_pair_start", JSONObject().put("p_code", prefs.pairCode).put("p_device_id", prefs.deviceId)) } catch (_: Throwable) {}
                }
                main.postDelayed({ if (resumed && !prefs.paired) pollPairing(msg) }, 2000)
            }
        }
    }

    /** send_key and site for the "send a note" QR. */
    private fun loadInfo(): Boolean {
        val key = prefs.tvKey ?: return false
        return try {
            val info = Api.obj("tv_info", JSONObject().put("p_tv_key", key))
            prefs.sendKey = info.optString("send_key")
            prefs.siteUrl = info.optString("site_url").takeIf { it.startsWith("https://") }
            true
        } catch (_: Throwable) { false }
    }

    // Home ----------------------------------------------------------------------------------

    private fun showHome() {
        val sendKey = prefs.sendKey
        if (sendKey == null) {
            screen("Casa TV", "Conectando…", null) {}
            io.execute { loadInfo(); main.postDelayed({ if (resumed) showHome() }, if (prefs.sendKey == null) 3000 else 0) }
            return
        }
        val name = prefs.name ?: "TV"
        val site = prefs.siteUrl ?: Config.SITE_URL
        val url = "$site/#k=$sendKey&tv=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        var first: View? = null
        screen("Casa TV · $name", "Escanea el código con el celular para enviar una nota", url) {
            val st = Ui.text(context, "", 18f, Ui.TEXT)
            val ov = Ui.text(context, "", 18f, Ui.TEXT).apply { setPadding(0, dp(10), 0, 0) }
            val rm = Ui.text(context, "", 18f, Ui.TEXT).apply { setPadding(0, dp(10), 0, 0); setLineSpacing(0f, 1.3f) }
            addView(st); addView(ov); addView(rm)
            statusView = st; overlayView = ov; remoteView = rm
            first = buttons(
                "Configurar mando" to ::setupRemote,
                "Probar sonido" to ::playChime,
                "Ver la última nota" to { CasaService.replay(this@MainActivity) },
            )
            buttons("Permitir notas sobre otras apps" to ::askOverlay, "Desvincular" to ::unpair)
        }
        first?.requestFocus()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!resumed || !prefs.paired) return
        remoteView?.textSize = 18f
        val online = System.currentTimeMillis() - CasaService.lastSyncOk < 15_000
        statusView?.text = if (online) "●  Lista para recibir notas" else "○  Conectando con internet…"
        statusView?.setTextColor(if (online) Ui.OK else Ui.DIM)

        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        overlayView?.text = if (overlay) "●  Las notas aparecen sobre cualquier app"
                            else "○  Las notas solo aparecen con Casa TV abierta (se arregla al configurar el mando)"
        overlayView?.setTextColor(if (overlay) Ui.OK else Ui.DIM)

        remoteView?.text = when {
            Remote.available -> "●  Mando a distancia listo"
            else -> "○  Mando sin activar: toca «Configurar mando»"
        }
        remoteView?.setTextColor(if (Remote.available) Ui.OK else Ui.DIM)
        main.postDelayed(::refreshStatus, 2000)
    }

    private fun setupRemote() {
        remoteView?.text = "…  Si aparece «¿Permitir depuración?», marca «Permitir siempre» y acepta"
        remoteView?.setTextColor(Ui.TEXT)
        main.removeCallbacksAndMessages(null)
        CasaService.setupRemote(this) { ok ->
            if (ok || Remote.available) { refreshStatus(); return@setupRemote }
            // No ADB from the TV itself (Fire OS 7.6+): use the accessibility service instead.
            remoteView?.text = "○  Activa el servicio «Casa TV · mando» en Ajustes → Accesibilidad.\n" +
                "     Si no aparece, desde la computadora:\n" +
                "     adb shell settings put secure enabled_accessibility_services com.casa.tv/com.casa.tv.RemoteService\n" +
                "     adb shell settings put secure accessibility_enabled 1"
            remoteView?.setTextColor(Ui.TEXT)
            remoteView?.textSize = 15f
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {}
            main.postDelayed(::refreshStatus, 60000)
        }
    }

    private fun askOverlay() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {
            // Fire TV has no such screen: the remote setup grants it through ADB instead.
            setupRemote()
        }
    }

    private fun playChime() {
        try {
            android.media.MediaPlayer.create(this, R.raw.chime)?.apply { setOnCompletionListener { it.release() }; start() }
        } catch (_: Throwable) {}
    }

    private fun unpair() {
        prefs.unpair()
        stopService(Intent(this, CasaService::class.java))
        Remote.disconnect()
        showPairing()
    }
}
