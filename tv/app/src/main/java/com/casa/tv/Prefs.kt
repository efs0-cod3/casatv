package com.casa.tv

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("casa", Context.MODE_PRIVATE)

    private fun str(k: String) = sp.getString(k, null)?.takeIf { it.isNotEmpty() }
    private fun put(k: String, v: String?) = sp.edit().putString(k, v).apply()

    var tvKey: String?
        get() = str("tv_key")
        set(v) = put("tv_key", v)
    var name: String?
        get() = str("name")
        set(v) = put("name", v)
    var sendKey: String?
        get() = str("send_key")
        set(v) = put("send_key", v)
    var siteUrl: String?
        get() = str("site_url")
        set(v) = put("site_url", v)

    /** Set once the TV allowed this app's ADB key, so the service may reconnect on its own. */
    var remoteOk: Boolean
        get() = sp.getBoolean("remote_ok", false)
        set(v) = sp.edit().putBoolean("remote_ok", v).apply()

    val paired get() = tvKey != null

    val deviceId: String
        get() = str("device_id") ?: UUID.randomUUID().toString().also { put("device_id", it) }

    val pairCode: String
        get() = str("pair_code") ?: randomCode().also { put("pair_code", it) }

    fun unpair() {
        sp.edit().remove("tv_key").remove("name").remove("send_key").remove("pair_code").apply()
    }

    private fun randomCode(): String {
        val abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..32).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }
}
