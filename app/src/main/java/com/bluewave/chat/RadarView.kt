package com.bluewave.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val coreFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1A007BFF
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = 0xCC007BFF.toInt() // Bright Electric Blue rings
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x44007BFF
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pulseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = 0xFF00A8FF.toInt()
    }

    private val centerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x44007BFF
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0066FF.toInt()
    }

    private var sweepAngle = 0f
    private var pulseFraction = 0f
    private val ovalRect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweepAngle = it.animatedValue as Float
            pulseFraction = (sweepAngle % 180f) / 180f
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
        val maxRadius = minOf(cx, cy) - 10f
        if (maxRadius <= 0f) return

        // Soft bright-blue radar disc backdrop
        canvas.drawCircle(cx, cy, maxRadius, coreFillPaint)

        // Crosshair axes
        canvas.drawLine(cx - maxRadius, cy, cx + maxRadius, cy, gridPaint)
        canvas.drawLine(cx, cy - maxRadius, cx, cy + maxRadius, gridPaint)

        // Sweeping radar beam
        ovalRect.set(cx - maxRadius, cy - maxRadius, cx + maxRadius, cy + maxRadius)
        sweepPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(0x00007BFF, 0x5500A8FF, 0x00007BFF),
            floatArrayOf(0f, 0.22f, 0.35f)
        )
        canvas.save()
        canvas.rotate(sweepAngle, cx, cy)
        canvas.drawArc(ovalRect, 0f, 95f, true, sweepPaint)
        canvas.restore()

        // Bright Blue concentric rings
        canvas.drawCircle(cx, cy, maxRadius * 0.35f, ringPaint)
        canvas.drawCircle(cx, cy, maxRadius * 0.68f, ringPaint)
        canvas.drawCircle(cx, cy, maxRadius, ringPaint)

        // Expanding sonar wave
        pulseRingPaint.alpha = ((1f - pulseFraction) * 210).toInt().coerceIn(0, 210)
        canvas.drawCircle(cx, cy, maxRadius * pulseFraction, pulseRingPaint)

        // Center beacon
        canvas.drawCircle(cx, cy, 22f, centerGlowPaint)
        canvas.drawCircle(cx, cy, 11f, centerDotPaint)
    }
}
