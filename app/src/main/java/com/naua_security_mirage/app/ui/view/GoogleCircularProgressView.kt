package com.naua_security_mirage.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Material Design Indeterminate Circular Progress Indicator (Google Play style).
 *
 * Implemented with direct canvas drawing and Handler frame scheduling, ensuring
 * it continues to rotate and smoothly expand/contract even on devices where system
 * animations are disabled in Accessibility or Developer Options.
 */
class GoogleCircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val strokeWidthPx = 2.5f * density

    private var running = false
    private var startAngle = 0f
    private var sweepAngle = 40f
    private var isGrowing = true

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
        visibility = VISIBLE
        postInvalidateDelayed(16)
    }

    fun stop() {
        running = false
        visibility = GONE
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (!running) {
                running = true
                postInvalidateDelayed(16)
            }
        } else {
            running = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) {
            running = true
            postInvalidateDelayed(16)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
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

        // 1. Static faint background circular track
        canvas.drawOval(arcRect, trackPaint)

        // 2. Google-style rotating and expanding/contracting arc
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, indicatorPaint)

        if (running) {
            startAngle = (startAngle + 5.5f) % 360f
            if (isGrowing) {
                sweepAngle += 3f
                if (sweepAngle >= 250f) isGrowing = false
            } else {
                sweepAngle -= 3f
                if (sweepAngle <= 35f) isGrowing = true
            }
            postInvalidateDelayed(16)
        }
    }
}
