package com.casa.tv

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** Every app on the TV, as a grid of tiles. */
class AllAppsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> Ui.dp(this, v) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(36), dp(56), dp(36))
        }
        col.addView(Ui.text(this, "Todas las apps", 32f, Ui.TEXT, bold = true).apply { setPadding(0, 0, 0, dp(20)) })

        val perRow = 5
        val w = dp(160)
        val h = w * 9 / 16
        var row: LinearLayout? = null
        Apps.all(this).forEachIndexed { i, app ->
            if (i % perRow == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { if (i > 0) topMargin = dp(18) })
            }
            val t = Ui.tile(this, app.label, app.banner, app.icon, w, h) { Apps.open(this, app); finish() }
            row!!.addView(t, LinearLayout.LayoutParams(w, h).apply { if (i % perRow > 0) marginStart = dp(18) })
            if (i == 0) t.post { t.requestFocus() }
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(col)
        })
    }
}
