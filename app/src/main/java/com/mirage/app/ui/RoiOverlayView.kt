package com.mirage.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Lets the user drag-to-move and corner-drag-to-resize a rectangle over the
 * live preview. Coordinates are in this VIEW's own pixel space -- whoever
 * consumes [roiInViewCoords] is responsible for mapping it into the
 * analysis image's coordinate space (see CoordinateMapper), since the
 * preview view and the analysis image are generally different sizes/aspect
 * ratios.
 */
class RoiOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val handlePaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val handleRadius = 24f
    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private enum class DragMode { NONE, MOVE, RESIZE }

    // Default ROI: a centered box roughly a third of the view, set properly
    // once the view has a real size (see onSizeChanged).
    var roi = RectF(100f, 100f, 300f, 250f)
        private set

    var onRoiChanged: ((RectF) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0 && w > 0 && h > 0) {
            val cx = w / 2f
            val cy = h / 2f
            val halfW = w / 6f
            val halfH = h / 6f
            roi = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(roi, boxPaint)
        canvas.drawCircle(roi.right, roi.bottom, handleRadius / 2f, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = if (abs(x - roi.right) < handleRadius && abs(y - roi.bottom) < handleRadius) {
                    DragMode.RESIZE
                } else if (roi.contains(x, y)) {
                    DragMode.MOVE
                } else {
                    DragMode.NONE
                }
                lastTouchX = x
                lastTouchY = y
                return dragMode != DragMode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                when (dragMode) {
                    DragMode.MOVE -> {
                        roi.offset(dx, dy)
                        clampToView()
                    }
                    DragMode.RESIZE -> {
                        roi.right = (roi.right + dx).coerceAtLeast(roi.left + 40f).coerceAtMost(width.toFloat())
                        roi.bottom = (roi.bottom + dy).coerceAtLeast(roi.top + 40f).coerceAtMost(height.toFloat())
                    }
                    DragMode.NONE -> return false
                }
                lastTouchX = x
                lastTouchY = y
                invalidate()
                onRoiChanged?.invoke(RectF(roi))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
            }
        }
        return super.onTouchEvent(event)
    }

    private fun clampToView() {
        if (roi.left < 0) roi.offset(-roi.left, 0f)
        if (roi.top < 0) roi.offset(0f, -roi.top)
        if (roi.right > width) roi.offset(width - roi.right, 0f)
        if (roi.bottom > height) roi.offset(0f, height - roi.bottom)
    }

    fun roiInViewCoords(): RectF = RectF(roi)

    fun centerOn(x: Float, y: Float, widthFraction: Float = 0.34f, heightFraction: Float = 0.30f) {
        if (width <= 0 || height <= 0) return
        val rw = (width * widthFraction).coerceAtLeast(80f).coerceAtMost(width.toFloat())
        val rh = (height * heightFraction).coerceAtLeast(70f).coerceAtMost(height.toFloat())
        val left = (x - rw / 2f).coerceIn(0f, width - rw)
        val top = (y - rh / 2f).coerceIn(0f, height - rh)
        roi = RectF(left, top, left + rw, top + rh)
        invalidate()
        onRoiChanged?.invoke(RectF(roi))
    }
}

