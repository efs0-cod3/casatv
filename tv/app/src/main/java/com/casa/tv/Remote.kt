package com.casa.tv

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Runs remote-control keys on this TV through its own ADB ("Depuración ADB" on Fire TV).
 * The first connection makes the TV ask "¿Permitir depuración?" once; after that it is silent.
 */
object Remote {
    private var dadb: Dadb? = null

    private val keys = mapOf(
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "ok" to 23,
        "back" to 4, "home" to 3, "menu" to 82,
        "play_pause" to 85, "rewind" to 89, "forward" to 90,
        "vol_up" to 24, "vol_down" to 25, "mute" to 164,
        "sleep" to 223, "wake" to 224,
    )

    val supported get() = Build.VERSION.SDK_INT >= 26   // dadb needs java.util.Base64

    /** Why the last connection failed, shown on the setup screen. */
    @Volatile var lastError: String? = null

    val ready: Boolean
        @Synchronized get() = dadb != null

    /** Blocks while the TV shows the permission prompt. Call from a background thread. */
    @Synchronized
    fun connect(ctx: Context): Boolean {
        if (!supported) { lastError = "Android ${Build.VERSION.SDK_INT}: hace falta Fire OS 7 o superior"; return false }
        if (dadb != null) return true
        val keys = try { keyPair(ctx) } catch (e: Throwable) { return fail("clave", e) }
        // adbd may listen only on the network address on some Fire OS versions, so try that too.
        val d = (listOf("127.0.0.1") + lanAddresses()).firstNotNullOfOrNull { host ->
            try {
                Dadb.create(host, 5555, keys).also { it.shell("echo ok") }
            } catch (e: Throwable) { fail(host, e); null }
        } ?: return false
        lastError = null
        dadb = d
        Prefs(ctx).remoteOk = true
        // Notes may now draw over other apps; Fire TV has no settings screen for this.
        try { d.shell("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow") } catch (e: Throwable) { fail("permiso", e) }
        return true
    }

    private fun fail(where: String, e: Throwable): Boolean {
        lastError = "$where: ${e.javaClass.simpleName}${e.message?.let { " – $it" } ?: ""}"
        Log.w("CasaTV", "remote $where", e)
        return false
    }

    private fun lanAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    } catch (_: Throwable) { emptyList() }

    @Synchronized
    fun shell(ctx: Context, cmd: String): Boolean {
        val d = dadb ?: if (connect(ctx)) dadb else null
        if (d == null) return false
        return try {
            d.shell(cmd)
            true
        } catch (e: Throwable) {
            try { d.close() } catch (_: Throwable) {}
            dadb = null
            false
        }
    }

    /** ADB or the accessibility service: either is enough for the phone's remote. */
    val available: Boolean get() = ready || RemoteService.instance != null

    /** ADB when it works; otherwise media keys, the volume control and the accessibility service. */
    fun key(ctx: Context, name: String): Boolean {
        val code = keys[name] ?: return false
        if (ready && shell(ctx, "input keyevent $code")) return true
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (name) {
            "play_pause", "rewind", "forward" -> {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                true
            }
            "vol_up" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); true }
            "vol_down" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); true }
            "mute" -> Build.VERSION.SDK_INT >= 23 &&
                run { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); true }
            else -> RemoteService.instance?.key(name) ?: false
        }
    }

    @Synchronized
    fun disconnect() {
        try { dadb?.close() } catch (_: Throwable) {}
        dadb = null
    }

    private fun keyPair(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }
}
