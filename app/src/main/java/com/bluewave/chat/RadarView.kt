package com.bluewave.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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

    var onTelemetryTick: ((sweepDeg: Float) -> Unit)? = null

    private val bgDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF041326.toInt()
    }

    private val topoLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x3300E5FF
    }

    private val tacGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x2638BDF8
    }

    private val outerBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF00A8FF.toInt()
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xCC38BDF8.toInt()
    }

    private val degreeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7DD3FC.toInt()
        textSize = 15f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        color = 0x8800A8FF.toInt()
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
    private var lastTelemetryUpdate = 0L
    private val ovalRect = RectF()
    private val clipPath = Path()

    private val blipAngles = floatArrayOf(42f, 132f, 228f, 305f, 18f, 175f, 265f, 340f)
    private val blipDistances = floatArrayOf(0.55f, 0.68f, 0.48f, 0.62f, 0.76f, 0.42f, 0.72f, 0.50f)

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            if (isScanningActive) {
                sweepAngle = it.animatedValue as Float
                val now = System.currentTimeMillis()
                if (now - lastTelemetryUpdate > 450L) {
                    lastTelemetryUpdate = now
                    onTelemetryTick?.invoke(sweepAngle)
                }
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

        // 1. Tactical Military Disc + Clipped Topographic Map Contours
        canvas.drawCircle(cx, cy, outerRadius, bgDiscPaint)

        canvas.save()
        clipPath.reset()
        clipPath.addCircle(cx, cy, innerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // Military Square Sector Grid inside radar
        val step = innerRadius / 3.5f
        var gx = cx - innerRadius
        while (gx <= cx + innerRadius) {
            canvas.drawLine(gx, cy - innerRadius, gx, cy + innerRadius, tacGridPaint)
            gx += step
        }
        var gy = cy - innerRadius
        while (gy <= cy + innerRadius) {
            canvas.drawLine(cx - innerRadius, gy, cx + innerRadius, gy, tacGridPaint)
            gy += step
        }

        // Topographic Elevation Contours
        canvas.drawOval(cx - innerRadius * 0.8f, cy - innerRadius * 0.45f, cx + innerRadius * 0.2f, cy + innerRadius * 0.35f, topoLinePaint)
        canvas.drawOval(cx - innerRadius * 0.55f, cy - innerRadius * 0.25f, cx + innerRadius * 0.05f, cy + innerRadius * 0.18f, topoLinePaint)
        canvas.drawOval(cx - innerRadius * 0.1f, cy - innerRadius * 0.1f, cx + innerRadius * 0.75f, cy + innerRadius * 0.65f, topoLinePaint)
        canvas.restore()

        // 2. Outer Compass Bezel & Degree Markings
        canvas.drawCircle(cx, cy, outerRadius, outerBezelPaint)
        canvas.drawCircle(cx, cy, innerRadius, outerBezelPaint)

        for (deg in 0 until 360 step 5) {
            val rad = Math.toRadians((deg - 90).toDouble())
            val isMajor = deg % 30 == 0
            val startR = if (isMajor) outerRadius - 13f else outerRadius - 6f
            val endR = outerRadius - 2f
            canvas.drawLine(
                cx + (cos(rad) * startR).toFloat(),
                cy + (sin(rad) * startR).toFloat(),
                cx + (cos(rad) * endR).toFloat(),
                cy + (sin(rad) * endR).toFloat(),
                tickPaint
            )
            if (isMajor) {
                val textR = outerRadius - 23f
                canvas.drawText(
                    deg.toString(),
                    cx + (cos(rad) * textR).toFloat(),
                    cy + (sin(rad) * textR).toFloat() + 5f,
                    degreeTextPaint
                )
            }
        }

        // 3. Polar Spokes & Range Rings
        for (deg in 0 until 360 step 30) {
            val rad = Math.toRadians(deg.toDouble())
            canvas.drawLine(
                cx, cy,
                cx + (cos(rad) * innerRadius).toFloat(),
                cy + (sin(rad) * innerRadius).toFloat(),
                tacGridPaint
            )
        }
        canvas.drawCircle(cx, cy, innerRadius * 0.33f, ringPaint)
        canvas.drawCircle(cx, cy, innerRadius * 0.66f, ringPaint)

        // 4. Sweeping Beam
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

        // 5. Locked Target Blips
        for (i in 0 until detectedTargetCount) {
            val rad = Math.toRadians(blipAngles[i].toDouble())
            val dist = innerRadius * blipDistances[i]
            val bx = cx + (cos(rad) * dist).toFloat()
            val by = cy + (sin(rad) * dist).toFloat()

            canvas.drawCircle(bx, by, 14f, blipGlowPaint)
            canvas.drawCircle(bx, by, 5.5f, blipCorePaint)
            canvas.drawRect(bx - 12f, by - 12f, bx + 12f, by + 12f, blipBracketPaint)
        }

        canvas.drawCircle(cx, cy, 11f, blipGlowPaint)
        canvas.drawCircle(cx, cy, 5.5f, outerBezelPaint)
    }
}
