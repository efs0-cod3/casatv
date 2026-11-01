package com.casa.tv

import android.content.Context
import android.os.Build
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

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

    val ready: Boolean
        @Synchronized get() = dadb != null

    /** Blocks while the TV shows the permission prompt. Call from a background thread. */
    @Synchronized
    fun connect(ctx: Context): Boolean {
        if (!supported) return false
        if (dadb != null) return true
        return try {
            val d = Dadb.create("127.0.0.1", 5555, keyPair(ctx))
            d.shell("echo ok")
            dadb = d
            Prefs(ctx).remoteOk = true
            // Notes may now draw over other apps; Fire TV has no settings screen for this.
            d.shell("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
            true
        } catch (e: Throwable) {
            false
        }
    }

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

    fun key(ctx: Context, name: String): Boolean {
        val code = keys[name] ?: return false
        return shell(ctx, "input keyevent $code")
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
