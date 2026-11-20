package com.naua_security_mirage.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Dynamic country flag view rendering national flag emoji for any country. */
class AuroraFlagView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var code: String = "ch"
        set(value) {
            field = value
            emoji = countryCodeToEmoji(value)
            invalidate()
        }

    private var emoji: String = "🇨🇭"
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        textPaint.textSize = h * 0.95f
        val yPos = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(emoji, w / 2f, yPos, textPaint)
    }

    companion object {
        fun countryCodeToEmoji(code: String?): String = CountryFlagHelper.countryCodeToEmoji(code)
    }
}
