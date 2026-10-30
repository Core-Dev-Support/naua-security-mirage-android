package com.naua_security_mirage.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Speed sparkline from the aurora design: 300x72 viewport, 40-point ring buffer,
 * smooth mid-point cubic paths for down (good) and up (cool) plus a soft fill.
 */
class AuroraSparkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Point(val d: Float, val u: Float)

    private val hist = MutableList(40) { Point(0f, 0f) }
    private var lastD = 0f
    private var lastU = 0f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        style = Paint.Style.STROKE
        color = 0x14FFFFFF
    }
    private val dnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF5EE0A0.toInt()
    }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xCC62C8FF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF5EE0A0.toInt()
    }
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF06070B.toInt()
    }

    fun setLive(down: Float, up: Float) {
        lastD = down
        lastU = up
        invalidate()
    }

    fun pushTick(down: Float, up: Float) {
        lastD = down
        lastU = up
        hist.add(Point(down, up))
        hist.removeAt(0)
        invalidate()
    }

    fun reset() {
        for (i in hist.indices) hist[i] = Point(0f, 0f)
        lastD = 0f
        lastU = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0x575EE0A0, 0x005EE0A0,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawGrid()
        if (fillPaint.shader == null) return

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 8f
        val n = hist.size
        var max = 60f
        hist.forEach { max = maxOf(max, it.d, it.u) }
        max *= 1.15f

        fun x(i: Int): Float = i * (w / (n - 1))
        fun y(v: Float): Float = h - pad - (v / max) * (h - 2 * pad)
        fun py(i: Int, key: Int): Float = y(if (key == 0) hist[i].d else hist[i].u)

        fun smoothPath(key: Int): android.graphics.Path {
            val p = android.graphics.Path()
            p.moveTo(x(0), py(0, key))
            for (i in 1 until n) {
                val px = x(i)
                val mx = (x(i - 1) + px) / 2f
                p.cubicTo(mx, py(i - 1, key), mx, py(i, key), px, py(i, key))
            }
            return p
        }

        // fill under the download line
        val fill = smoothPath(0)
        fill.lineTo(w, h)
        fill.lineTo(0f, h)
        fill.close()
        canvas.drawPath(fill, fillPaint)

        canvas.drawPath(smoothPath(1), upPaint)
        canvas.drawPath(smoothPath(0), dnPaint)

        val ex = x(n - 1)
        val ey = y(lastD)
        val r = 4.5f * resources.displayMetrics.density
        canvas.drawCircle(ex, ey, r, dotPaint)
        canvas.drawCircle(ex, ey, r, dotStroke)
    }

    private fun Canvas.drawGrid() {
        val w = width.toFloat()
        val h = height.toFloat()
        this.drawLine(0f, h * 0.34f, w, h * 0.34f, gridPaint)
        this.drawLine(0f, h * 0.68f, w, h * 0.68f, gridPaint)
    }
}
