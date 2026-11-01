package com.casa.tv

import android.content.Context
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

class Note(val id: String, val sender: String, val png: ByteArray)

/** The note card, shared by the overlay and NoteActivity. `onDone(reply)`: null means closed. */
object NoteView {
    val replies = listOf("❤️", "👍", "😂", "😮", "Ya voy", "OK")

    fun build(ctx: Context, note: Note, onDone: (String?) -> Unit): View {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        var done = false
        val finish = { r: String? -> if (!done) { done = true; onDone(r) } }

        val root = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (e.action == KeyEvent.ACTION_UP) finish(null)
                    return true
                }
                return super.dispatchKeyEvent(e)
            }
        }
        root.setBackgroundColor(0xD9000000.toInt())

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.round(ctx, Ui.PANEL, Ui.LINE, 28)
            setPadding(dp(28), dp(22), dp(28), dp(22))
        }
        val who = if (note.sender.isBlank()) "✏️  Nueva nota" else "✏️  Nota de ${note.sender}"
        card.addView(Ui.text(ctx, who, 26f, Ui.TEXT, bold = true))

        val screenH = ctx.resources.displayMetrics.heightPixels
        val imgH = (screenH * 0.56).toInt()
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            BitmapFactory.decodeByteArray(note.png, 0, note.png.size)?.let { setImageBitmap(it) }
            background = Ui.round(ctx, 0xFF10131B.toInt(), Ui.LINE, 18)
            clipToOutline = true
        }
        card.addView(img, LinearLayout.LayoutParams(imgH * 4 / 3, imgH).apply { topMargin = dp(14) })

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        var first: View? = null
        for (r in replies) {
            val b = Ui.button(ctx, r, if (r.length <= 2) 26f else 20f) { finish(r) }
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6); marginEnd = dp(6)
            })
            if (first == null) first = b
        }
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })
        card.addView(Ui.text(ctx, "Elige una respuesta  ·  Atrás para cerrar", 14f, Ui.DIM).apply {
            setPadding(0, dp(12), 0, 0)
        })

        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.post { first?.requestFocus() }
        return root
    }
}
