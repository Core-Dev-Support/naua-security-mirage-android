package com.naua_security_mirage.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * Material Design Indeterminate Circular Progress Indicator (Google Play style).
 *
 * Implemented with time-based delta rendering and hardware VSYNC scheduling via
 * postInvalidateOnAnimation(), ensuring silky-smooth 60-120fps rotation without any
 * hitching, stuttering, or frame drop slowdowns even under heavy system load.
 */
class GoogleCircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val strokeWidthPx = 2.5f * density

    private var running = false
    private var startTime = 0L

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = 0x33E8A33D.toInt()
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        color = 0xFFE8A33D.toInt()
    }

    private val arcRect = RectF()

    fun setColor(color: Int) {
        indicatorPaint.color = color
        trackPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(color, 55)
        invalidate()
    }

    fun start() {
        if (running && visibility == VISIBLE) return
        running = true
        startTime = SystemClock.uptimeMillis()
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        startTime = 0L
        visibility = GONE
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (!running) {
                running = true
                startTime = SystemClock.uptimeMillis()
                postInvalidateOnAnimation()
            }
        } else {
            running = false
            startTime = 0L
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) {
            running = true
            startTime = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        startTime = 0L
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultSize = (34f * density).toInt()
        val w = resolveSize(defaultSize, widthMeasureSpec)
        val h = resolveSize(defaultSize, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = strokeWidthPx / 2f + 1f * density
        arcRect.set(pad, pad, w.toFloat() - pad, h.toFloat() - pad)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE) return

        val now = SystemClock.uptimeMillis()
        if (startTime == 0L) startTime = now
        val elapsed = (now - startTime).coerceAtLeast(0L)

        // Silky-smooth 360-degree rotation every 1000ms (~0.36 deg/ms) based on absolute elapsed time
        val startAngle = (elapsed * 0.36f) % 360f

        // Natural sinusoidal arc breathing between 35° and 240° over a 1400ms cycle
        val cycle = (elapsed % 1400L) / 1400.0
        val sinFactor = ((Math.sin(cycle * 2.0 * Math.PI - Math.PI / 2.0) + 1.0) / 2.0).toFloat()
        val sweepAngle = 35f + 205f * sinFactor

        // 1. Static faint background circular track
        canvas.drawOval(arcRect, trackPaint)

        // 2. Google-style rotating and expanding/contracting arc
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, indicatorPaint)

        if (running) {
            postInvalidateOnAnimation()
        }
    }
}
