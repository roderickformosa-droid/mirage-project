package com.mirage.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Compact scene-evidence map. It never invents exact cue distances. */
class EvidencePathView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA71E7FF.toInt(); strokeWidth = 3f }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF71E7FF.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt(); textSize = 18f }
    private var targetRange: Double? = null
    private var cues: List<Pair<String,String>> = emptyList()

    fun setEvidence(rangeM: Double?, encoded: String) {
        targetRange = rangeM
        cues = encoded.split('|').mapNotNull { token ->
            val p = token.split(':', limit = 2)
            if (p.size == 2) p[0] to p[1].uppercase() else null
        }.take(4)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width * 0.34f
        val top = 28f
        val bottom = height - 28f
        canvas.drawLine(x, top, x, bottom, linePaint)
        canvas.drawCircle(x, bottom, 6f, dotPaint)
        canvas.drawText("YOU", x + 10f, bottom + 5f, textPaint)
        canvas.drawCircle(x, top, 6f, dotPaint)
        canvas.drawText(targetRange?.let { "TGT %.0fm".format(it) } ?: "TGT", x + 10f, top + 5f, textPaint)
        cues.forEachIndexed { i, (label, band) ->
            val frac = when (band) { "NEAR" -> 0.78f; "MID" -> 0.52f; "FAR" -> 0.26f; else -> 0.62f - i * 0.10f }
            val y = top + (bottom - top) * frac
            canvas.drawCircle(x, y, 5f, dotPaint)
            val suffix = if (band == "UNKNOWN") " ?" else " ${band.take(1)}"
            canvas.drawText(label.take(7) + suffix, x + 10f, y + 5f, textPaint)
        }
    }
}
