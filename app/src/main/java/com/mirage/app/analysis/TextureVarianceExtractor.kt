package com.mirage.app.analysis

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.ArrayDeque

/**
 * Direct port of Stage 1's texture_variance.py: mean per-pixel temporal
 * standard deviation over a rolling window (`activity_index`, population
 * std / ddof=0, matching numpy's default), plus Laplacian variance of the
 * current frame (`spatial_texture`) so a flat/blank ROI reads as "nothing
 * to measure" rather than being confused with "no motion."
 *
 * Implemented with batched Mat arithmetic (not a per-pixel Kotlin loop) to
 * keep this affordable at the target 5-10 Hz analysis rate on-device.
 */
class TextureVarianceExtractor(private val windowFrames: Int = 15) {

    data class Result(val activityIndex: Double, val spatialTexture: Double, val bufferFill: Int)

    private val buffer = ArrayDeque<Mat>()

    fun reset() {
        buffer.forEach { it.release() }
        buffer.clear()
    }

    fun process(roiGrayFloat: Mat): Result {
        buffer.addLast(roiGrayFloat.clone())
        while (buffer.size > windowFrames) buffer.pollFirst()?.release()

        val lap = Mat()
        Imgproc.Laplacian(roiGrayFloat, lap, CvType.CV_32F)
        val meanOut = MatOfDouble()
        val stdOut = MatOfDouble()
        Core.meanStdDev(lap, meanOut, stdOut)
        val stdVal = stdOut.toArray().getOrElse(0) { 0.0 }
        val spatialTexture = stdVal * stdVal

        if (buffer.size < 3) {
            return Result(0.0, spatialTexture, buffer.size)
        }

        val size = roiGrayFloat.size()
        val sum = Mat.zeros(size, CvType.CV_32F)
        for (f in buffer) Core.add(sum, f, sum)
        val meanMat = Mat()
        Core.multiply(sum, Scalar(1.0 / buffer.size), meanMat)

        val sqDiffSum = Mat.zeros(size, CvType.CV_32F)
        val diff = Mat()
        val sq = Mat()
        for (f in buffer) {
            Core.subtract(f, meanMat, diff)
            Core.multiply(diff, diff, sq)
            Core.add(sqDiffSum, sq, sqDiffSum)
        }
        val varMat = Mat()
        Core.multiply(sqDiffSum, Scalar(1.0 / buffer.size), varMat)
        val stdMat = Mat()
        Core.sqrt(varMat, stdMat)
        val activityIndex = Core.mean(stdMat).`val`[0]

        sum.release(); meanMat.release(); sqDiffSum.release(); diff.release(); sq.release()
        varMat.release(); stdMat.release(); lap.release()

        return Result(activityIndex, spatialTexture, buffer.size)
    }
}
