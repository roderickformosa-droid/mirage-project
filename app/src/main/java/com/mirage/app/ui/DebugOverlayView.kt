package com.mirage.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.mirage.app.analysis.AnalysisResult

/**
 * Visualizes what the algorithms are actually tracking, directly analogous
 * to Stage 1's debug_overlay.py: the ROI box, per-cell optical-flow arrows,
 * and per-patch block-matching arrows (colored by confidence, matching the
 * min_response threshold used to decide which patches are trusted).
 *
 * Vectors are amplified for visibility using the SAME amplification
 * factors as the desktop tool, so the two are visually comparable.
 */
class DebugOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val FLOW_AMPLIFY = 15f
        const val BM_AMPLIFY = 15f
        const val MIN_RESPONSE = 0.15f
    }

    private var result: AnalysisResult? = null
    private var roiViewRect: RectF = RectF()

    private val roiPaint = Paint().apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val flowPaint = Paint().apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val bmGoodPaint = Paint().apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val bmBadPaint = Paint().apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val disturbancePaint = Paint().apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 12f
    }

    fun update(newResult: AnalysisResult, roiInViewCoords: RectF) {
        result = newResult
        roiViewRect = roiInViewCoords
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return

        canvas.drawRect(roiViewRect, roiPaint)

        // Scale factor from analysis-ROI-local pixel coords to view pixel coords.
        val sx = roiViewRect.width() / r.roiWidthPx.toFloat().coerceAtLeast(1f)
        val sy = roiViewRect.height() / r.roiHeightPx.toFloat().coerceAtLeast(1f)

        r.flowVectors.forEach { v ->
            val x0 = roiViewRect.left + v.x * sx
            val y0 = roiViewRect.top + v.y * sy
            val x1 = roiViewRect.left + (v.x + v.dx * FLOW_AMPLIFY) * sx
            val y1 = roiViewRect.top + (v.y + v.dy * FLOW_AMPLIFY) * sy
            canvas.drawLine(x0, y0, x1, y1, flowPaint)
        }

        r.blockVectors.forEach { v ->
            val x0 = roiViewRect.left + v.x * sx
            val y0 = roiViewRect.top + v.y * sy
            val x1 = roiViewRect.left + (v.x + v.dx * BM_AMPLIFY) * sx
            val y1 = roiViewRect.top + (v.y + v.dy * BM_AMPLIFY) * sy
            canvas.drawLine(x0, y0, x1, y1, if (v.response >= MIN_RESPONSE) bmGoodPaint else bmBadPaint)
        }

        if (r.stabDisturbance) {
            canvas.drawRect(2f, 2f, width - 2f, height - 2f, disturbancePaint)
        }
    }
}
