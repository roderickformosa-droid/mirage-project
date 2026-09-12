package com.mirage.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.mirage.app.analysis.AnalysisResult
import kotlin.math.max

/** Always-on field overlay for AUTO ROI, moving wind cues, clock direction, and green stability status. */
class WindCueOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var result: AnalysisResult? = null

    private val redStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val cyanStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 30f; style = Paint.Style.FILL
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 24f; style = Paint.Style.FILL
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val greenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 145, 45); style = Paint.Style.FILL
    }
    private val darkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt(); style = Paint.Style.FILL
    }

    fun update(newResult: AnalysisResult) {
        result = newResult
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return
        if (r.fullWidthPx <= 0 || r.fullHeightPx <= 0) return

        // PreviewView is normally center-cropped. Use the same center-crop approximation here.
        val scale = max(width.toFloat() / r.fullWidthPx, height.toFloat() / r.fullHeightPx)
        val drawnW = r.fullWidthPx * scale
        val drawnH = r.fullHeightPx * scale
        val ox = (width - drawnW) / 2f
        val oy = (height - drawnH) / 2f
        fun mapRect(l: Int, t: Int, rr: Int, b: Int) = RectF(
            ox + l * scale, oy + t * scale, ox + rr * scale, oy + b * scale
        )

        // AUTO-selected mirage ROI.
        val mirageRect = mapRect(
            r.roiLeftPx, r.roiTopPx,
            r.roiLeftPx + r.roiWidthPx, r.roiTopPx + r.roiHeightPx
        )
        canvas.drawRect(mirageRect, cyanStroke)
        drawLabel(canvas, mirageRect.left, mirageRect.top - 8f,
            "MIRAGE ${r.mirageClockDirection}", r.mirageStable, r.mirageStableForSec)

        for (cue in r.motionCues) {
            val rect = mapRect(cue.leftPx, cue.topPx, cue.rightPx, cue.bottomPx)
            canvas.drawOval(rect, redStroke)
            val speed = "%.1f–%.1f m/s".format(cue.estimatedWindMinMps, cue.estimatedWindMaxMps)
            val label = "${cue.label}  ${cue.clockDirection}  WIND $speed"
            drawLabel(canvas, rect.left, (rect.top - 10f).coerceAtLeast(36f), label, cue.stable, cue.stableForSec)
            val detail = buildString {
                append("conf %.0f%%".format(cue.confidence * 100.0))
                cue.distanceM?.let { append("  %.0fm".format(it)) }
                cue.physicalMotionMps?.let { append("  obj %.1fm/s".format(it)) }
            }
            canvas.drawText(detail, rect.left, rect.bottom + 26f, smallTextPaint)
        }
    }

    private fun drawLabel(canvas: Canvas, x: Float, y: Float, label: String, stable: Boolean, stableSec: Double) {
        val pad = 8f
        val labelW = textPaint.measureText(label)
        val h = 38f
        canvas.drawRect(x, y - h + 6f, x + labelW + pad * 2, y + 8f, darkFill)
        canvas.drawText(label, x + pad, y, textPaint)
        if (stable) {
            val stableText = "● STABLE %.1fs".format(stableSec)
            val sw = smallTextPaint.measureText(stableText)
            canvas.drawRect(x, y + 12f, x + sw + pad * 2, y + 48f, greenFill)
            canvas.drawText(stableText, x + pad, y + 40f, smallTextPaint)
        }
    }
}
