package com.mirage.app.analysis

import android.graphics.Rect
import android.graphics.RectF

/**
 * Maps a rectangle drawn in PreviewView's on-screen pixel space into the
 * ImageAnalysis buffer's pixel space, so the region the user drags on
 * screen is the same region actually analyzed.
 *
 * HONEST LIMITATION (documented rather than silently assumed away): this
 * uses a simple proportional scale, which is only pixel-accurate if the
 * preview and the analysis stream share the same aspect ratio and the
 * PreviewView isn't cropping (letterboxing) the image differently than the
 * analysis buffer. This build configures Preview and ImageAnalysis to the
 * same target aspect ratio specifically to keep this assumption
 * reasonable, but it has not been verified pixel-perfect on real hardware.
 * The debug overlay draws vectors at wherever analysis actually happened,
 * which doubles as a visual check: if the vectors don't line up with the
 * cyan ROI box on your device, this mapping needs refinement -- tell me
 * and send a screenshot/recording and I'll fix the mapping specifically.
 */
class CoordinateMapper(
    private val viewWidth: Int,
    private val viewHeight: Int,
    private val analysisWidth: Int,
    private val analysisHeight: Int
) {
    fun viewRectToAnalysisRect(viewRect: RectF): Rect {
        val sx = analysisWidth.toFloat() / viewWidth.toFloat()
        val sy = analysisHeight.toFloat() / viewHeight.toFloat()
        val left = (viewRect.left * sx).toInt().coerceIn(0, analysisWidth - 1)
        val top = (viewRect.top * sy).toInt().coerceIn(0, analysisHeight - 1)
        val right = (viewRect.right * sx).toInt().coerceIn(left + 1, analysisWidth)
        val bottom = (viewRect.bottom * sy).toInt().coerceIn(top + 1, analysisHeight)
        return Rect(left, top, right, bottom)
    }
}
