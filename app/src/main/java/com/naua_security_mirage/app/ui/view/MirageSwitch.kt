package com.naua_security_mirage.app.ui.view

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.Checkable
import com.naua_security_mirage.app.util.AnimationHelper

class MirageSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Checkable {

    private var checked = false
    private var progress = 0f
    private var animHandle: AnimationHelper.AnimationHandle? = null
    private val argbEvaluator = ArgbEvaluator()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()

    private var trackOffColor = Color.parseColor("#1F2333")
    private var trackBorderOffColor = Color.parseColor("#32384E")
    private var trackOnColor = Color.parseColor("#E67E22")
    private var trackBorderOnColor = Color.parseColor("#F39C12")

    private var thumbOffColor = Color.parseColor("#9AA0B4")
    private var thumbOnColor = Color.parseColor("#FFFFFF")
    private var lightMode = false

    // aurora design: 52x32 track, off track is translucent white, on track is an accent gradient
    private var auroraStyle = false
    private var trackShader: Shader? = null
    private var thumbShader: Shader? = null
    private var shaderKey = ""

    fun setStyle(aurora: Boolean) {
        auroraStyle = aurora
        requestLayout()
        invalidate()
    }

    var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    fun setOnCheckedChangeListener(listener: (View, Boolean) -> Unit) {
        onCheckedChangeListener = { isChecked -> listener(this, isChecked) }
    }

    fun setActiveColor(color: Int) {
        if (color == 0) {
            resetColors()
            return
        }
        trackOnColor = color
        trackBorderOnColor = color
        invalidate()
    }

    fun setLightMode(isLight: Boolean) {
        lightMode = isLight
        if (isLight) {
            trackOffColor = Color.parseColor("#E2E8F0")
            trackBorderOffColor = Color.parseColor("#CBD5E1")
            thumbOffColor = Color.parseColor("#94A3B8")
        } else {
            trackOffColor = Color.parseColor("#1F2333")
            trackBorderOffColor = Color.parseColor("#32384E")
            thumbOffColor = Color.parseColor("#9AA0B4")
        }
        invalidate()
    }

    fun resetColors() {
        trackOnColor = Color.parseColor("#E67E22")
        trackBorderOnColor = Color.parseColor("#F39C12")
        trackOffColor = if (lightMode) Color.parseColor("#E2E8F0") else Color.parseColor("#1F2333")
        trackBorderOffColor = if (lightMode) Color.parseColor("#CBD5E1") else Color.parseColor("#32384E")
        thumbOffColor = if (lightMode) Color.parseColor("#94A3B8") else Color.parseColor("#9AA0B4")
        thumbOnColor = Color.parseColor("#FFFFFF")
        invalidate()
    }

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        thumbPaint.setShadowLayer(dpToPx(2f), 0f, dpToPx(1f), Color.argb(90, 0, 0, 0))
    }

    override fun isChecked(): Boolean = checked

    override fun setChecked(checked: Boolean) {
        setChecked(checked, animate = true)
    }

    fun setChecked(checked: Boolean, animate: Boolean) {
        if (this.checked == checked && progress == (if (checked) 1f else 0f)) return
        this.checked = checked

        animHandle?.cancel()
        if (animate && isAttachedToWindow) {
            val startProgress = progress
            val target = if (checked) 1f else 0f
            animHandle = AnimationHelper.animateDirect(
                durationMs = 200,
                interpolator = AnimationHelper.EaseInOutCubic,
                onUpdate = { fraction ->
                    progress = startProgress + (target - startProgress) * fraction
                    invalidate()
                },
                onEnd = {
                    progress = target
                    invalidate()
                }
            )
        } else {
            progress = if (checked) 1f else 0f
            invalidate()
        }
    }

    override fun toggle() {
        val newChecked = !checked
        try {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Throwable) {}
        AnimationHelper.bounceClick(this, minScale = 0.93f, durationMs = 180)
        setChecked(newChecked, animate = true)
        onCheckedChangeListener?.invoke(newChecked)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animHandle?.cancel()
    }

    override fun performClick(): Boolean {
        toggle()
        return super.performClick()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = dpToPx(if (auroraStyle) 52f else 44f).toInt()
        val desiredH = dpToPx(if (auroraStyle) 32f else 24f).toInt()

        val width = resolveSize(desiredW, widthMeasureSpec)
        val height = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cornerRadius = h / 2f

        val currentTrackColor = argbEvaluator.evaluate(progress, trackOffColor, trackOnColor) as Int
        val currentBorderColor = argbEvaluator.evaluate(progress, trackBorderOffColor, trackBorderOnColor) as Int

        val strokeInset = trackBorderPaint.strokeWidth / 2f
        trackRect.set(strokeInset, strokeInset, w - strokeInset, h - strokeInset)

        if (auroraStyle) {
            // on-track gradient; rebuild only on size change
            val key = "${w}x${h}"
            if (key != shaderKey || trackShader == null) {
                shaderKey = key
                trackShader = LinearGradient(
                    0f, 0f, w, h,
                    Color.parseColor("#FFD089"), Color.parseColor("#FFA928"),
                    Shader.TileMode.CLAMP
                )
            }
            trackPaint.shader = if (progress > 0f) trackShader else null
            trackBorderPaint.color = if (progress > 0f) Color.parseColor("#4DFFA928")
            else Color.parseColor("#14FFFFFF")
        } else {
            trackPaint.shader = null
        }

        trackPaint.color = currentTrackColor
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)

        trackBorderPaint.color = currentBorderColor
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackBorderPaint)

        val padding = dpToPx(2.5f)
        val thumbRadius = cornerRadius - padding
        val startX = padding + thumbRadius
        val endX = w - padding - thumbRadius
        val currentThumbX = startX + (endX - startX) * progress
        val currentThumbY = h / 2f

        val currentThumbColor = argbEvaluator.evaluate(progress, thumbOffColor, thumbOnColor) as Int
        thumbPaint.color = currentThumbColor
        if (auroraStyle) {
            // thumb: white -> #E6E9F1, like in the reference
            val t = currentThumbY - thumbRadius
            val b = currentThumbY + thumbRadius
            thumbPaint.shader = LinearGradient(0f, t, 0f, b, 0xFFFFFFFF.toInt(), 0xFFE6E9F1.toInt(), Shader.TileMode.CLAMP)
            thumbPaint.setShadowLayer(dpToPx(3.5f), 0f, dpToPx(2f), 0x61000000)
        } else {
            thumbPaint.shader = null
        }
        canvas.drawCircle(currentThumbX, currentThumbY, thumbRadius, thumbPaint)
        if (auroraStyle) thumbPaint.setShadowLayer(dpToPx(2f), 0f, dpToPx(1f), Color.argb(90, 0, 0, 0))
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
