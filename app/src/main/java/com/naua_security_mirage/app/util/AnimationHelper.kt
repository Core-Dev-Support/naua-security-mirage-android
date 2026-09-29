package com.naua_security_mirage.app.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.animation.Interpolator
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.sin

object AnimationHelper {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeAnimations = WeakHashMap<View, AnimationHandle>()

    interface AnimationHandle {
        fun cancel()
        val isRunning: Boolean
    }

    val EaseOutCubic = Interpolator { t ->
        val inv = 1f - t
        1f - inv * inv * inv
    }

    val EaseInOutCubic = Interpolator { t ->
        if (t < 0.5f) {
            4f * t * t * t
        } else {
            val inv = -2f * t + 2f
            1f - (inv * inv * inv) / 2f
        }
    }

    val EaseOutQuad = Interpolator { t ->
        1f - (1f - t) * (1f - t)
    }

    class SpringOvershoot(private val tension: Float = 1.4f) : Interpolator {
        override fun getInterpolation(t: Float): Float {
            val t_ = t - 1f
            return t_ * t_ * ((tension + 1f) * t_ + tension) + 1f
        }
    }

    fun animateDirect(
        durationMs: Long,
        interpolator: Interpolator = EaseOutCubic,
        onUpdate: (fraction: Float) -> Unit,
        onEnd: (() -> Unit)? = null
    ): AnimationHandle {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            var handle: AnimationHandle? = null
            mainHandler.post {
                handle = animateDirect(durationMs, interpolator, onUpdate, onEnd)
            }
            return object : AnimationHandle {
                override fun cancel() {
                    mainHandler.post { handle?.cancel() }
                }
                override val isRunning: Boolean get() = handle?.isRunning ?: false
            }
        }

        val effectiveDuration = if (durationMs <= 0L) 1L else durationMs
        val startUptime = SystemClock.uptimeMillis()
        var running = true

        val handle = object : AnimationHandle {
            override var isRunning: Boolean = true
                internal set

            override fun cancel() {
                if (!isRunning) return
                isRunning = false
                running = false
            }
        }

        val choreographer = Choreographer.getInstance()
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return

                val elapsed = SystemClock.uptimeMillis() - startUptime
                val rawProgress = (elapsed.toFloat() / effectiveDuration).coerceIn(0f, 1f)
                val interpolated = interpolator.getInterpolation(rawProgress)

                onUpdate(interpolated)

                if (rawProgress < 1f && running) {
                    choreographer.postFrameCallback(this)
                } else if (running) {
                    running = false
                    handle.isRunning = false
                    onEnd?.invoke()
                }
            }
        }

        choreographer.postFrameCallback(frameCallback)
        return handle
    }

    fun loopDirect(
        periodMs: Long,
        onFrame: (cycleProgress: Float) -> Unit
    ): AnimationHandle {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            var handle: AnimationHandle? = null
            mainHandler.post {
                handle = loopDirect(periodMs, onFrame)
            }
            return object : AnimationHandle {
                override fun cancel() {
                    mainHandler.post { handle?.cancel() }
                }
                override val isRunning: Boolean get() = handle?.isRunning ?: false
            }
        }

        val startUptime = SystemClock.uptimeMillis()
        var running = true

        val handle = object : AnimationHandle {
            override var isRunning: Boolean = true
                internal set

            override fun cancel() {
                if (!isRunning) return
                isRunning = false
                running = false
            }
        }

        val choreographer = Choreographer.getInstance()
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return

                val elapsed = SystemClock.uptimeMillis() - startUptime
                val cycleProgress = (elapsed % periodMs).toFloat() / periodMs

                onFrame(cycleProgress)

                if (running) {
                    choreographer.postFrameCallback(this)
                }
            }
        }

        choreographer.postFrameCallback(frameCallback)
        return handle
    }

    fun cancelViewAnimation(view: View) {
        activeAnimations[view]?.cancel()
        activeAnimations.remove(view)
    }

    fun fadeAndSlideIn(
        view: View,
        fromX: Float = 40f,
        durationMs: Long = 230,
        interpolator: Interpolator = EaseOutCubic
    ): AnimationHandle {
        cancelViewAnimation(view)
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationX = fromX

        val handle = animateDirect(
            durationMs = durationMs,
            interpolator = interpolator,
            onUpdate = { fraction ->
                view.alpha = fraction
                view.translationX = fromX * (1f - fraction)
            },
            onEnd = {
                view.alpha = 1f
                view.translationX = 0f
            }
        )
        activeAnimations[view] = handle
        return handle
    }

    fun fadeAndSlideOut(
        view: View,
        toX: Float = -40f,
        durationMs: Long = 200,
        interpolator: Interpolator = EaseInOutCubic,
        onEnd: (() -> Unit)? = null
    ): AnimationHandle {
        cancelViewAnimation(view)
        val startAlpha = view.alpha

        val handle = animateDirect(
            durationMs = durationMs,
            interpolator = interpolator,
            onUpdate = { fraction ->
                view.alpha = startAlpha * (1f - fraction)
                view.translationX = toX * fraction
            },
            onEnd = {
                view.visibility = View.GONE
                view.translationX = 0f
                view.alpha = 1f
                onEnd?.invoke()
            }
        )
        activeAnimations[view] = handle
        return handle
    }

    fun spin(
        view: View,
        durationMs: Long = 850,
        rotations: Float = 1f,
        onEnd: (() -> Unit)? = null
    ): AnimationHandle {
        cancelViewAnimation(view)
        val startRotation = view.rotation
        val targetRotation = startRotation + (360f * rotations)

        val handle = animateDirect(
            durationMs = durationMs,
            interpolator = EaseInOutCubic,
            onUpdate = { fraction ->
                view.rotation = startRotation + (targetRotation - startRotation) * fraction
            },
            onEnd = {
                view.rotation = targetRotation % 360f
                onEnd?.invoke()
            }
        )
        activeAnimations[view] = handle
        return handle
    }

    fun bounceClick(
        view: View,
        minScale: Float = 0.91f,
        durationMs: Long = 180,
        onEnd: (() -> Unit)? = null
    ): AnimationHandle {
        cancelViewAnimation(view)

        val handle = animateDirect(
            durationMs = durationMs,
            interpolator = EaseInOutCubic,
            onUpdate = { fraction ->

                val scale = if (fraction < 0.45f) {
                    val sub = fraction / 0.45f
                    1f - (1f - minScale) * sub
                } else {
                    val sub = (fraction - 0.45f) / 0.55f
                    val spring = SpringOvershoot(1.6f).getInterpolation(sub)
                    minScale + (1f - minScale) * spring
                }
                view.scaleX = scale
                view.scaleY = scale
            },
            onEnd = {
                view.scaleX = 1f
                view.scaleY = 1f
                onEnd?.invoke()
            }
        )
        activeAnimations[view] = handle
        return handle
    }

    fun popIn(
        view: View,
        durationMs: Long = 240
    ): AnimationHandle {
        cancelViewAnimation(view)
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.scaleX = 0.88f
        view.scaleY = 0.88f

        val overshoot = SpringOvershoot(1.3f)
        val handle = animateDirect(
            durationMs = durationMs,
            interpolator = EaseOutCubic,
            onUpdate = { fraction ->
                view.alpha = fraction.coerceIn(0f, 1f)
                val scale = 0.88f + 0.12f * overshoot.getInterpolation(fraction)
                view.scaleX = scale
                view.scaleY = scale
            },
            onEnd = {
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
            }
        )
        activeAnimations[view] = handle
        return handle
    }

    fun popOut(
        view: View,
        durationMs: Long = 200,
        onEnd: (() -> Unit)? = null
    ): AnimationHandle {
        cancelViewAnimation(view)
        val startAlpha = view.alpha
        val startScale = view.scaleX

        val handle = animateDirect(
            durationMs = durationMs,
            interpolator = EaseOutCubic,
            onUpdate = { fraction ->
                view.alpha = (startAlpha * (1f - fraction)).coerceIn(0f, 1f)
                val scale = startScale - 0.12f * fraction
                view.scaleX = scale
                view.scaleY = scale
            },
            onEnd = {
                view.visibility = View.GONE
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
                onEnd?.invoke()
            }
        )
        activeAnimations[view] = handle
        return handle
    }

    fun shake(
        view: View,
        amplitudePx: Float = 14f,
        cycles: Int = 3,
        durationMs: Long = 300
    ): AnimationHandle {
        cancelViewAnimation(view)

        val handle = animateDirect(
            durationMs = durationMs,
            interpolator = EaseOutQuad,
            onUpdate = { fraction ->
                val decay = 1f - fraction
                val rad = fraction * cycles * 2.0 * PI
                val offset = sin(rad).toFloat() * amplitudePx * decay
                view.translationX = offset
            },
            onEnd = {
                view.translationX = 0f
            }
        )
        activeAnimations[view] = handle
        return handle
    }

    fun spin(
        view: View,
        degrees: Float = 360f,
        durationMs: Long = 360,
        onEnd: (() -> Unit)? = null
    ): AnimationHandle {
        cancelViewAnimation(view)
        val startRotation = view.rotation

        val handle = animateDirect(
            durationMs = durationMs,
            interpolator = EaseOutCubic,
            onUpdate = { fraction ->
                view.rotation = startRotation + degrees * fraction
                val microScale = if (fraction < 0.5f) {
                    1f - 0.12f * (fraction / 0.5f)
                } else {
                    0.88f + 0.12f * ((fraction - 0.5f) / 0.5f)
                }
                view.scaleX = microScale
                view.scaleY = microScale
            },
            onEnd = {
                view.rotation = startRotation + degrees
                view.scaleX = 1f
                view.scaleY = 1f
                onEnd?.invoke()
            }
        )
        activeAnimations[view] = handle
        return handle
    }

    fun startBreathing(
        view: View,
        minScale: Float = 0.96f,
        maxScale: Float = 1.04f,
        periodMs: Long = 1400
    ): AnimationHandle {
        cancelViewAnimation(view)

        val handle = loopDirect(periodMs) { progress ->
            val wave = (sin(progress * 2.0 * PI - PI / 2.0).toFloat() + 1f) / 2f
            val currentScale = minScale + (maxScale - minScale) * wave
            view.scaleX = currentScale
            view.scaleY = currentScale
        }
        activeAnimations[view] = handle
        return handle
    }

    fun startGlowPulse(
        view: View,
        minScale: Float = 0.98f,
        maxScale: Float = 1.15f,
        minAlpha: Float = 0.25f,
        maxAlpha: Float = 0.85f,
        periodMs: Long = 1400
    ): AnimationHandle {
        cancelViewAnimation(view)
        view.visibility = View.VISIBLE

        val handle = loopDirect(periodMs) { progress ->
            val wave = (sin(progress * 2.0 * PI - PI / 2.0).toFloat() + 1f) / 2f
            val currentScale = minScale + (maxScale - minScale) * wave
            val currentAlpha = minAlpha + (maxAlpha - minAlpha) * wave
            view.scaleX = currentScale
            view.scaleY = currentScale
            view.alpha = currentAlpha
        }
        activeAnimations[view] = handle
        return handle
    }
}
