package com.mirage.app.analysis

import org.opencv.core.Mat
import org.opencv.video.Video
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Direct port of Stage 1's optical_flow.py: dense Farneback flow over the
 * ROI, reduced to a single (angle, magnitude) via the MEDIAN active vector
 * (not mean), same noise floor concept, same reasoning about why median
 * beats mean for outlier rejection.
 *
 * Parameters match config.yaml's `optical_flow:` section by name so the two
 * tools can be configured identically.
 */
class OpticalFlowExtractor(
    private val pyrScale: Double = 0.5,
    private val levels: Int = 3,
    private val winsize: Int = 15,
    private val iterations: Int = 3,
    private val polyN: Int = 5,
    private val polySigma: Double = 1.2,
    private val noiseFloorPx: Double = 0.05,
    private val debugArrowStep: Int = 10
) {
    data class Result(
        val angleDeg: Double, val magnitude: Double, val meanMagnitude: Double,
        val activeFraction: Double, val vectors: List<VectorArrow>
    )

    private var prevGray: Mat? = null

    fun reset() { prevGray = null }

    fun process(roiGray: Mat): Result {
        val prev = prevGray
        if (prev == null || prev.size() != roiGray.size()) {
            prevGray = roiGray.clone()
            return Result(Double.NaN, 0.0, 0.0, 0.0, emptyList())
        }

        val flow = Mat()
        Video.calcOpticalFlowFarneback(
            prev, roiGray, flow, pyrScale, levels, winsize, iterations, polyN, polySigma, 0
        )
        prevGray = roiGray.clone()

        val h = flow.rows(); val w = flow.cols()
        val flowData = FloatArray(h * w * 2)
        flow.get(0, 0, flowData)

        val activeDx = ArrayList<Float>()
        val activeDy = ArrayList<Float>()
        var activeCount = 0
        var sumMag = 0.0
        val vectors = ArrayList<VectorArrow>()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = (y * w + x) * 2
                val fx = flowData[idx]
                val fy = flowData[idx + 1]
                val mag = hypot(fx.toDouble(), fy.toDouble())
                sumMag += mag
                if (mag > noiseFloorPx) {
                    activeDx.add(fx); activeDy.add(fy); activeCount++
                }
                if (y % debugArrowStep == 0 && x % debugArrowStep == 0) {
                    vectors.add(VectorArrow(x.toFloat(), y.toFloat(), fx, fy))
                }
            }
        }

        val activeFraction = if (h * w > 0) activeCount.toDouble() / (h * w) else 0.0
        val medDx = median(activeDx)
        val medDy = median(activeDy)
        val angleDeg = ((Math.toDegrees(atan2(medDy, medDx)) % 180.0) + 180.0) % 180.0
        val magnitude = hypot(medDx, medDy)
        val meanMag = if (h * w > 0) sumMag / (h * w) else 0.0

        return Result(angleDeg, magnitude, meanMag, activeFraction, vectors)
    }

    private fun median(list: List<Float>): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid].toDouble()
    }
}
