package com.naua_security_mirage.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Tiny 20x14dp country flag drawn with fractional stripes (FR, BY). */
class AuroraFlagView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var code: String = "fr"
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    private val stripeW get() = width / 3f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = resources.displayMetrics.density * 3f
        if (code == "by") {
            // Belarus: red over green, white ornament stripes on the left
            stripe(canvas, 0f, 0f, w, h, r, COLOR_BY_RED)
            stripe(canvas, 0f, h * 0.66f, w, h, r, COLOR_BY_GREEN)
            stripe(canvas, 0f, 0f, w * 0.07f, h, r, COLOR_BY_WHITE)
            stripe(canvas, w * 0.07f, 0f, w * 0.13f, h, r, COLOR_BY_RED)
            stripe(canvas, w * 0.13f, 0f, w * 0.19f, h, r, COLOR_BY_WHITE)
        } else if (code == "ch") {
            // Switzerland: red field with a white cross
            stripe(canvas, 0f, 0f, w, h, r, COLOR_CH_RED)
            stripe(canvas, w * 0.42f, h * 0.2f, w * 0.58f, h * 0.8f, r * 0.5f, COLOR_CH_WHITE)
            stripe(canvas, w * 0.26f, h * 0.38f, w * 0.74f, h * 0.62f, r * 0.5f, COLOR_CH_WHITE)
        } else {
            // France: blue | white | red
            stripe(canvas, 0f, 0f, w, h, r, COLOR_FR_BLUE)
            stripe(canvas, w / 3f, 0f, w * 2f / 3f, h, r, COLOR_FR_WHITE)
            stripe(canvas, w * 2f / 3f, 0f, w, h, r, COLOR_FR_RED)
        }
    }

    private fun stripe(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        r: Float,
        color: Int
    ) {
        fill.color = color
        path.reset()
        path.addRoundRect(left, top, right, bottom, floatArrayOf(r, r, r, r, r, r, r, r), Path.Direction.CW)
        canvas.drawPath(path, fill)
    }

    companion object {
        private val COLOR_FR_BLUE = 0xFF0055A4.toInt()
        private val COLOR_FR_WHITE = 0xFFFFFFFF.toInt()
        private val COLOR_FR_RED = 0xFFEF4135.toInt()
        private val COLOR_CH_RED = 0xFFD52B1E.toInt()
        private val COLOR_CH_WHITE = 0xFFFFFFFF.toInt()
        private val COLOR_BY_RED = 0xFFC8313E.toInt()
        private val COLOR_BY_GREEN = 0xFF4AA657.toInt()
        private val COLOR_BY_WHITE = 0xFFFFFFFF.toInt()
    }
}
