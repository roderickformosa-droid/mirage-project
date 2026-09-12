package com.mirage.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.mirage.app.R
import java.util.ArrayDeque

/**
 * Minimal rolling line graph. Deliberately simple -- this is a field-test
 * sanity-check tool, not a polished charting UI. Auto-scales its vertical
 * axis to whatever range of values it has seen recently, so different
 * measurements (angle in degrees, tiny sub-pixel magnitudes, etc.) all
 * remain visible without per-graph tuning.
 */
class GraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxPoints = 150
    private val values = ArrayDeque<Float>()

    private val linePaint = Paint().apply {
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val axisPaint = Paint().apply {
        color = Color.parseColor("#444444")
        strokeWidth = 1f
    }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.GraphView)
        linePaint.color = a.getColor(R.styleable.GraphView_lineColor, Color.WHITE)
        a.recycle()
    }

    fun addValue(v: Float) {
        if (v.isNaN() || v.isInfinite()) return
        values.addLast(v)
        while (values.size > maxPoints) values.removeFirst()
        postInvalidate()
    }

    fun clear() {
        values.clear()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(0f, h / 2f, w, h / 2f, axisPaint)

        if (values.size < 2) return

        val list = values.toList()
        val minV = list.min()
        val maxV = list.max()
        val range = (maxV - minV).let { if (it < 1e-6f) 1e-6f else it }

        val stepX = w / (maxPoints - 1).toFloat()
        var prevX = 0f
        var prevY = h - ((list[0] - minV) / range) * h
        val startIdx = maxPoints - list.size
        for (i in list.indices) {
            val x = (startIdx + i) * stepX
            val y = h - ((list[i] - minV) / range) * h
            if (i > 0) canvas.drawLine(prevX, prevY, x, y, linePaint)
            prevX = x
            prevY = y
        }
    }
}
