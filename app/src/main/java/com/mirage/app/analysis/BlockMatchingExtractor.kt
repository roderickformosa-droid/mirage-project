package com.mirage.app.analysis

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct port of Stage 1's block_matching.py: a grid of patches, each
 * tracked via phase correlation against a FIXED reference frame (not
 * frame-to-frame), reduced to median angle/magnitude + circular angular
 * dispersion across patches -- with the min_response confidence filter
 * that fixed a real bug found during desktop testing (low-texture patches
 * producing noisy, unreliable shift estimates were polluting the median).
 *
 * IMPORTANT CARRY-OVER FIX: cv2.phaseCorrelate mutates its input Mats in
 * place (confirmed on desktop). The OpenCV Android binding wraps the same
 * underlying C++ function, so the same risk applies here. Every patch is
 * explicitly `.clone()`-d before being passed to phaseCorrelate -- do not
 * remove these clones.
 */
class BlockMatchingExtractor(
    private val gridRows: Int = 6,
    private val gridCols: Int = 8,
    private val patchSize: Int = 24,
    private val minResponse: Double = 0.15
) {
    data class Result(
        val angleDeg: Double, val magnitude: Double, val meanMagnitude: Double,
        val angularDispersion: Double, val meanResponse: Double, val inlierFraction: Double,
        val vectors: List<VectorArrow>
    )

    private var referenceRoiGray: Mat? = null
    private val hannCache = HashMap<Pair<Int, Int>, Mat>()

    fun reset() { referenceRoiGray = null }

    private fun hann(w: Int, h: Int): Mat {
        val key = Pair(w, h)
        return hannCache.getOrPut(key) {
            val win = Mat()
            Imgproc.createHanningWindow(win, Size(w.toDouble(), h.toDouble()), CvType.CV_32F)
            win
        }
    }

    fun process(roiGrayFloat: Mat): Result {
        val h = roiGrayFloat.rows(); val w = roiGrayFloat.cols()
        val ref = referenceRoiGray
        if (ref == null || ref.size() != roiGrayFloat.size()) {
            referenceRoiGray = roiGrayFloat.clone()
            return emptyResult()
        }

        data class Patch(val cx: Double, val cy: Double, val dx: Double, val dy: Double, val response: Double)
        val patches = ArrayList<Patch>()

        for (r in 0 until gridRows) {
            for (c in 0 until gridCols) {
                val cy = ((r + 0.5) * h / gridRows).toInt()
                val cx = ((c + 0.5) * w / gridCols).toInt()
                val half = patchSize / 2
                val y0 = cy - half; val y1 = cy + half
                val x0 = cx - half; val x1 = cx + half
                if (y0 < 0 || x0 < 0 || y1 > h || x1 > w) continue

                // .clone() required -- see class-level comment. Submats are
                // VIEWS into the parent Mat; phaseCorrelate mutates its inputs.
                val refPatch = Mat(ref, Rect(x0, y0, patchSize, patchSize)).clone()
                val curPatch = Mat(roiGrayFloat, Rect(x0, y0, patchSize, patchSize)).clone()
                val window = hann(patchSize, patchSize)
                val response = DoubleArray(1)
                val shift = Imgproc.phaseCorrelate(refPatch, curPatch, window, response)
                patches.add(Patch(cx.toDouble(), cy.toDouble(), shift.x, shift.y, response[0]))
            }
        }

        if (patches.isEmpty()) return emptyResult()

        val trusted = patches.filter { it.response >= minResponse }
        val inlierFraction = trusted.size.toDouble() / patches.size
        val meanResponse = patches.map { it.response }.average()

        val vectors = patches.map {
            VectorArrow(it.cx.toFloat(), it.cy.toFloat(), it.dx.toFloat(), it.dy.toFloat(), it.response.toFloat())
        }

        if (trusted.isEmpty()) {
            return Result(Double.NaN, 0.0, 0.0, Double.NaN, meanResponse, 0.0, vectors)
        }

        val dxs = trusted.map { it.dx }
        val dys = trusted.map { it.dy }
        val medDx = median(dxs)
        val medDy = median(dys)
        val angleDeg = ((Math.toDegrees(atan2(medDy, medDx)) % 180.0) + 180.0) % 180.0
        val magnitude = hypot(medDx, medDy)
        val meanMag = trusted.map { hypot(it.dx, it.dy) }.average()

        // Circular dispersion (angle doubled since mirage angle is 0-180 axial, not 0-360 polar).
        val angles = trusted.map { atan2(it.dy, it.dx) }
        val sinSum = angles.map { sin(2 * it) }.average()
        val cosSum = angles.map { cos(2 * it) }.average()
        val resultantLength = hypot(sinSum, cosSum)
        val angularDispersion = 1.0 - resultantLength

        return Result(angleDeg, magnitude, meanMag, angularDispersion, meanResponse, inlierFraction, vectors)
    }

    private fun median(list: List<Double>): Double {
        val sorted = list.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun emptyResult() = Result(Double.NaN, 0.0, 0.0, Double.NaN, 0.0, 0.0, emptyList())
}
