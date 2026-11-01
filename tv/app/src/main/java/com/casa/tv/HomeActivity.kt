package com.casa.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Casa TV's home screen: clock and weather, the favourite apps, the note QR and shortcuts. */
class HomeActivity : Activity() {
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weather: TextView
    private lateinit var status: TextView
    private var weatherAt = 0L

    private val es = Locale("es", "DO")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.paired) {
            startActivity(Intent(this, MainActivity::class.java))
            return
        }
        CasaService.start(this)
        build()
        tick()
        if (System.currentTimeMillis() - weatherAt > 15 * 60_000) loadWeather()
    }

    override fun onPause() {
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    // Back on the home screen has nowhere to go.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(keyCode, event)

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(56), dp(36), dp(56), dp(28))
        }

        // Top: clock, date and weather on the left; the note QR on the right.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = Ui.text(this, "", 64f, Ui.TEXT, bold = true)
        date = Ui.text(this, "", 22f, Ui.DIM)
        weather = Ui.text(this, "", 22f, Ui.TEXT).apply { setPadding(0, dp(6), 0, 0) }
        status = Ui.text(this, "", 16f, Ui.DIM).apply { setPadding(0, dp(12), 0, 0) }
        left.addView(clock); left.addView(date); left.addView(weather); left.addView(status)
        top.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qrCard()?.let { top.addView(it) }
        root.addView(top)

        // Favourite apps, big.
        val favs = Apps.favorites(this)
        val tileH = dp(150)
        val favRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var first: View? = null
        favs.forEachIndexed { i, app ->
            val t = Ui.tile(this, app.label, app.banner, app.icon, tileH * 16 / 9, tileH) { Apps.open(this, app) }
            favRow.addView(t, LinearLayout.LayoutParams(tileH * 16 / 9, tileH).apply { if (i > 0) marginStart = dp(24) })
            if (first == null) first = t
        }
        root.addView(Ui.text(this, "Tus apps", 18f, Ui.DIM, bold = true).apply { setPadding(0, dp(26), 0, dp(12)) })
        root.addView(favRow)

        // Shortcuts.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items = mutableListOf<Pair<String, () -> Unit>>(
            "Todas las apps" to { startActivity(Intent(this, AllAppsActivity::class.java)) },
        )
        if (CasaService.lastNote != null) items += "✏️ Última nota" to { CasaService.replay(this) }
        items += "Ajustes de Casa TV" to { startActivity(Intent(this, MainActivity::class.java)) }
        items += "Ajustes del Fire TV" to { openSafe(Intent(Settings.ACTION_SETTINGS)) }
        items += "Inicio de Amazon" to ::stockHome
        items.forEachIndexed { i, (label, f) ->
            val b = Ui.button(this, label, 16f, f)
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) marginStart = dp(12) })
            if (first == null) first = b
        }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(30) })

        setContentView(root)
        first?.requestFocus()
    }

    private fun qrCard(): View? {
        val sendKey = prefs.sendKey ?: return null
        val name = prefs.name ?: "TV"
        val site = prefs.siteUrl ?: Config.SITE_URL
        val url = "$site/#k=$sendKey&tv=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val size = dp(130)
        val card = FrameLayout(this).apply {
            background = Ui.round(this@HomeActivity, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 16)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        card.addView(ImageView(this).apply { setImageBitmap(Qr.bitmap(url, 400)) }, FrameLayout.LayoutParams(size, size))
        col.addView(card)
        col.addView(Ui.text(this, "Envíame una nota", 15f, Ui.DIM).apply { setPadding(0, dp(8), 0, 0) })
        return col
    }

    private fun tick() {
        val now = Date()
        clock.text = SimpleDateFormat("h:mm", es).format(now)
        date.text = SimpleDateFormat("EEEE d 'de' MMMM", es).format(now).replaceFirstChar { it.uppercase() }
        val online = System.currentTimeMillis() - CasaService.lastSyncOk < 15_000
        status.text = if (online) "●  ${prefs.name ?: "TV"} · lista para recibir notas" else "○  Conectando…"
        status.setTextColor(if (online) Ui.OK else Ui.DIM)
        main.postDelayed(::tick, 5000)
    }

    // Same place as the original app: Santo Domingo.
    private fun loadWeather() {
        io.execute {
            try {
                val body = URL("https://api.open-meteo.com/v1/forecast?latitude=18.4861&longitude=-69.9312" +
                    "&current=temperature_2m,apparent_temperature,weather_code&timezone=America%2FSanto_Domingo").readText()
                val cur = JSONObject(body).getJSONObject("current")
                val t = Math.round(cur.getDouble("temperature_2m"))
                val feels = Math.round(cur.getDouble("apparent_temperature"))
                val text = "${sky(cur.optInt("weather_code"))}  $t°" + if (feels != t) "  ·  se siente $feels°" else ""
                weatherAt = System.currentTimeMillis()
                main.post { weather.text = text }
            } catch (_: Throwable) {}
        }
    }

    private fun sky(code: Int) = when (code) {
        0 -> "☀️ Despejado"
        1 -> "🌤️ Mayormente despejado"
        2 -> "⛅ Parcialmente nublado"
        3 -> "☁️ Nublado"
        45, 48 -> "🌫️ Niebla"
        in 51..57 -> "🌦️ Llovizna"
        in 61..67 -> "🌧️ Lluvia"
        in 80..82 -> "🌧️ Chubascos"
        in 95..99 -> "⛈️ Tormenta"
        else -> "🌡️"
    }

    /** Opens Amazon's home and leaves it alone for five minutes (search, library, profiles). */
    private fun stockHome() {
        prefs.homeBypassUntil = System.currentTimeMillis() + 5 * 60_000
        val i = packageManager.getLaunchIntentForPackage("com.amazon.tv.launcher")
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        openSafe(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openSafe(i: Intent) {
        try { startActivity(i) } catch (_: Throwable) {}
    }
}
