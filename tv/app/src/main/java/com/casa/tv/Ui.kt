package com.casa.tv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    const val BG = 0xFF0B0D12.toInt()
    const val PANEL = 0xFF161B26.toInt()
    const val LINE = 0xFF262D3D.toInt()
    const val TEXT = 0xFFF4F5F7.toInt()
    const val DIM = 0xFF8A93A8.toInt()
    const val OK = 0xFF34D399.toInt()
    const val ERR = 0xFFF87171.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun round(ctx: Context, fill: Int, stroke: Int = fill, radius: Int = 16) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(ctx, radius).toFloat()
        setStroke(dp(ctx, 2), stroke)
    }

    fun text(ctx: Context, s: CharSequence, sp: Float, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /**
     * A big D-pad tile for the home screen: the app's TV banner, or its icon and name. Grows and
     * gets a white frame when focused.
     */
    fun tile(ctx: Context, label: String, banner: Drawable?, icon: Drawable?, w: Int, h: Int, onClick: () -> Unit): View {
        val frame = FrameLayout(ctx).apply {
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), round(ctx, PANEL, TEXT, 16))
                addState(intArrayOf(), round(ctx, PANEL, LINE, 16))
            }
            val p = dp(ctx, 4)
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.07f else 1f).scaleY(if (f) 1.07f else 1f).setDuration(120).start() }
            clipToOutline = true
        }
        if (banner != null) {
            frame.addView(ImageView(ctx).apply { setImageDrawable(banner); scaleType = ImageView.ScaleType.CENTER_CROP },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            if (icon != null) col.addView(ImageView(ctx).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(h / 2, h / 2))
            col.addView(text(ctx, label, 16f, TEXT, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(ctx, 6), 0, 0) })
            frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        frame.layoutParams = ViewGroup.MarginLayoutParams(w, h)
        frame.contentDescription = label
        return frame
    }

    /** A D-pad friendly button: light when focused, panel-coloured otherwise. */
    fun button(ctx: Context, label: CharSequence, sp: Float = 18f, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = sp
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isFocusable = true
        isClickable = true
        val p = dp(ctx, 14)
        setPadding(p + p / 2, p, p + p / 2, p)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), round(ctx, TEXT, TEXT, 14))
            addState(intArrayOf(), round(ctx, PANEL, LINE, 14))
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(BG, TEXT)))
        setOnClickListener { onClick() }
    }
}
