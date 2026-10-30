package com.naua_security_mirage.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View

/** Rotating conic-gradient ring shown while connecting (design `.arc`). */
class AuroraArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var running = false
    private var angle = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density // 5px in a ~3x css, scale by density
        strokeCap = Paint.Cap.BUTT
    }
    private val path = Path()

    fun start() {
        if (running) return
        running = true
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val accent = 0xFFFFA928.toInt()
        paint.shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(0x00FFA928, 0x00FFA928, accent, accent),
            floatArrayOf(0f, 0.5f, 0.7f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (!running || paint.shader == null) return
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - paint.strokeWidth
        canvas.save()
        canvas.rotate(angle, cx, cy)
        path.reset()
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        path.addOval(rect, Path.Direction.CW)
        canvas.drawPath(path, paint)
        canvas.restore()
        angle = (angle + 6f) % 360f
        postInvalidateOnAnimation()
    }
}
