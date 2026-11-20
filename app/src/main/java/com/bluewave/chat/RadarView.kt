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
import kotlin.math.cos
import kotlin.math.sin

class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var detectedTargetCount: Int = 0
        set(value) {
            field = value.coerceIn(0, 8)
            invalidate()
        }

    var isScanningActive: Boolean = true
        set(value) {
            field = value
            if (value && !animator.isStarted) animator.start()
            invalidate()
        }

    private val bgDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF06162E.toInt() // Deep midnight-navy radar core
    }

    private val outerBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF00A8FF.toInt() // Bright electric blue bezel
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xCC38BDF8.toInt()
    }

    private val degreeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7DD3FC.toInt()
        textSize = 16f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val polarGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        color = 0x4438BDF8
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        color = 0x9900A8FF.toInt()
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val sweepLeadingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = 0xFF7DD3FC.toInt()
    }

    private val blipGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x5538BDF8
    }

    private val blipCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private val blipBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = 0xFF38BDF8.toInt()
    }

    private var sweepAngle = 45f
    private val ovalRect = RectF()

    // Fixed polar coordinates for detected peer blips inside the inner radar
    private val blipAngles = floatArrayOf(42f, 132f, 228f, 305f, 18f, 175f, 265f, 340f)
    private val blipDistances = floatArrayOf(0.55f, 0.68f, 0.48f, 0.62f, 0.76f, 0.42f, 0.72f, 0.50f)

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            if (isScanningActive) {
                sweepAngle = it.animatedValue as Float
                invalidate()
            }
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
        val outerRadius = minOf(cx, cy) - 8f
        if (outerRadius <= 24f) return

        val innerRadius = outerRadius * 0.78f

        // 1. Deep navy radar disc
        canvas.drawCircle(cx, cy, outerRadius, bgDiscPaint)
        canvas.drawCircle(cx, cy, outerRadius, outerBezelPaint)
        canvas.drawCircle(cx, cy, innerRadius, outerBezelPaint)

        // 2. Img-8 Outer Compass Degree Ticks & Numbers (0, 30, 60 ... 330)
        for (deg in 0 until 360 step 5) {
            val rad = Math.toRadians((deg - 90).toDouble())
            val isMajor = deg % 30 == 0
            val startR = if (isMajor) outerRadius - 14f else outerRadius - 7f
            val endR = outerRadius - 2f
            canvas.drawLine(
                cx + (cos(rad) * startR).toFloat(),
                cy + (sin(rad) * startR).toFloat(),
                cx + (cos(rad) * endR).toFloat(),
                cy + (sin(rad) * endR).toFloat(),
                tickPaint
            )
            if (isMajor) {
                val textR = outerRadius - 24f
                val tx = cx + (cos(rad) * textR).toFloat()
                val ty = cy + (sin(rad) * textR).toFloat() + 6f
                canvas.drawText(deg.toString(), tx, ty, degreeTextPaint)
            }
        }

        // 3. Img-9 Inner Polar Spokes & Concentric Sonar Rings
        for (deg in 0 until 360 step 30) {
            val rad = Math.toRadians(deg.toDouble())
            canvas.drawLine(
                cx, cy,
                cx + (cos(rad) * innerRadius).toFloat(),
                cy + (sin(rad) * innerRadius).toFloat(),
                polarGridPaint
            )
        }
        canvas.drawCircle(cx, cy, innerRadius * 0.33f, ringPaint)
        canvas.drawCircle(cx, cy, innerRadius * 0.66f, ringPaint)

        // 4. Sweeping Electric-Blue Beam (when scanning is active)
        if (isScanningActive) {
            ovalRect.set(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)
            sweepPaint.shader = SweepGradient(
                cx, cy,
                intArrayOf(0x0000A8FF, 0x6600A8FF, 0xCC38BDF8.toInt(), 0x0000A8FF),
                floatArrayOf(0f, 0.18f, 0.26f, 0.28f)
            )
            canvas.save()
            canvas.rotate(sweepAngle - 95f, cx, cy)
            canvas.drawArc(ovalRect, 0f, 95f, true, sweepPaint)
            canvas.restore()

            val beamRad = Math.toRadians(sweepAngle.toDouble())
            canvas.drawLine(
                cx, cy,
                cx + (cos(beamRad) * innerRadius).toFloat(),
                cy + (sin(beamRad) * innerRadius).toFloat(),
                sweepLeadingLinePaint
            )
        }

        // 5. Img-9 Locked-On Target Blips [●] for Nearby Peers
        for (i in 0 until detectedTargetCount) {
            val rad = Math.toRadians(blipAngles[i].toDouble())
            val dist = innerRadius * blipDistances[i]
            val bx = cx + (cos(rad) * dist).toFloat()
            val by = cy + (sin(rad) * dist).toFloat()

            canvas.drawCircle(bx, by, 14f, blipGlowPaint)
            canvas.drawCircle(bx, by, 5.5f, blipCorePaint)
            canvas.drawRect(bx - 12f, by - 12f, bx + 12f, by + 12f, blipBracketPaint)
        }

        // 6. Center Beacon Core
        canvas.drawCircle(cx, cy, 12f, blipGlowPaint)
        canvas.drawCircle(cx, cy, 6f, outerBezelPaint)
    }
}
