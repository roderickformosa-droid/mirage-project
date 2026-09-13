package com.mirage.app.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/** Compact field visual: shows where the observed wind cue is travelling. */
class WindDirectionVisualView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var clockDirection: String = "--"
    private var stable = false

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(105, 174, 255)
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x99FFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val observer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(40, 225, 108)
    }

    fun setWind(clock: String, isStable: Boolean) {
        clockDirection = clock
        stable = isStable
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (clockDirection == "--") return
        val cx = width * 0.52f
        val cy = height * 0.50f
        val hour = clockDirection.substringBefore(" ").toIntOrNull() ?: 3
        val angle = Math.toRadians(hour * 30.0)
        val dx = sin(angle).toFloat()
        val dy = -cos(angle).toFloat()
        val len = min(width, height) * 0.34f

        // Observer/reference marker.
        canvas.drawCircle(width * 0.18f, cy, 7f, observer)
        canvas.drawLine(width * 0.20f, cy, width * 0.38f, cy, guide)

        // Three flowing streamlines around the central reference point.
        for (offset in floatArrayOf(-28f, 0f, 28f)) {
            val sx = cx - dx * len + (-dy) * offset
            val sy = cy - dy * len + dx * offset
            val ex = cx + dx * len + (-dy) * offset
            val ey = cy + dy * len + dx * offset
            val path = Path()
            path.moveTo(sx, sy)
            val bend = 18f * if (offset == 0f) 0.6f else 1f
            path.cubicTo(
                sx + dx * len * .65f + (-dy) * bend,
                sy + dy * len * .65f + dx * bend,
                ex - dx * len * .65f - (-dy) * bend,
                ey - dy * len * .65f - dx * bend,
                ex, ey
            )
            canvas.drawPath(path, line)
            drawArrowHead(canvas, ex, ey, dx, dy)
        }
        if (stable) canvas.drawCircle(width - 18f, 18f, 7f, accent)
    }

    private fun drawArrowHead(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        val size = 16f
        val px = -dy
        val py = dx
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x - dx * size + px * size * .55f, y - dy * size + py * size * .55f)
        path.lineTo(x - dx * size - px * size * .55f, y - dy * size - py * size * .55f)
        path.close()
        val fill = Paint(line).apply { style = Paint.Style.FILL }
        canvas.drawPath(path, fill)
    }
}
