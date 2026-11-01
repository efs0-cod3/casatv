package com.casa.tv

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Supabase RPC over plain HTTPS. Call from a background thread. */
object Api {
    fun rpc(fn: String, args: JSONObject): String {
        val c = URL("${Config.SUPABASE_URL}/rest/v1/rpc/$fn").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.useCaches = false
            c.setRequestProperty("apikey", Config.SUPABASE_KEY)
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.use { it.write(args.toString().toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("$fn: $code $body")
            return body
        } finally {
            c.disconnect()
        }
    }

    fun obj(fn: String, args: JSONObject) = JSONObject(rpc(fn, args))
}
