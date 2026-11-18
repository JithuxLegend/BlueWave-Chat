package com.bluewave.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0x4D38BDF8 // Soft sky-blue ring
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x262563EB // Translucent blue sonar wave
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2563EB.toInt()
    }

    private var pulseFraction = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulseFraction = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = minOf(cx, cy) - 8f
        if (maxRadius <= 0f) return

        // Static concentric radar rings
        canvas.drawCircle(cx, cy, maxRadius * 0.35f, ringPaint)
        canvas.drawCircle(cx, cy, maxRadius * 0.68f, ringPaint)
        canvas.drawCircle(cx, cy, maxRadius, ringPaint)

        // Expanding animated sonar pulse
        val currentRadius = maxRadius * pulseFraction
        pulsePaint.alpha = ((1f - pulseFraction) * 70).toInt().coerceIn(0, 70)
        canvas.drawCircle(cx, cy, currentRadius, pulsePaint)

        // Center beacon dot
        canvas.drawCircle(cx, cy, 14f, centerDotPaint)
    }
}
