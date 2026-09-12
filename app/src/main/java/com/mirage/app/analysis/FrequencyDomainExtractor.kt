package com.mirage.app.analysis

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.ArrayDeque

/**
 * Direct port of Stage 1's frequency_domain.py: stacks one scanline column
 * over a rolling window of frames, per-row-mean-subtracts, runs an FFT
 * along the time axis for every row (Core.dft with DFT_ROWS -- OpenCV's
 * equivalent of applying numpy's rfft to every row of the stack at once),
 * then reports the dominant frequency, total AC spectral energy, and the
 * energy-weighted centroid frequency, excluding the DC bin -- identical
 * definitions to the desktop version.
 *
 * PERFORMANCE NOTE: the DFT itself is the only step that needs to go
 * through OpenCV's native code; everything else (building the stack,
 * mean-subtraction, power/energy/centroid math) is done in plain Kotlin
 * arrays via ONE bulk Mat.get/put call each, rather than per-pixel
 * Mat.get()/put() calls in a loop -- per-element JNI calls at this array
 * size were a measurable slowdown risk at the target 5-10 Hz analysis
 * rate, and this avoids it.
 *
 * KNOWN DIFFERENCE FROM DESKTOP: numpy's rfft and OpenCV's dft are
 * mathematically equivalent real-input FFTs, but are different
 * implementations (different libraries, different internal padding /
 * bin-ordering conventions). Bin frequencies (k * fps / window_frames) and
 * the overall shape of the result should match closely, but exact
 * floating-point values are not guaranteed identical to the desktop CSV --
 * treat this as "same measurement, different engine," and cross-check the
 * two during the field test rather than assuming bit-for-bit parity.
 */
class FrequencyDomainExtractor(
    private val windowFrames: Int = 64,
    private val fps: Double = 30.0
) {
    data class Result(
        val dominantFreqHz: Double, val spectralEnergy: Double,
        val spectralCentroidHz: Double, val bufferFill: Int
    )

    private val buffer = ArrayDeque<FloatArray>()
    private var lastResult = Result(0.0, 0.0, 0.0, 0)

    fun reset() {
        buffer.clear()
        lastResult = Result(0.0, 0.0, 0.0, 0)
    }

    /** roiGrayFloat: the analyzed ROI, already single-channel CV_32F. scanlineCol: which column to sample. */
    fun process(roiGrayFloat: Mat, scanlineCol: Int): Result {
        val h = roiGrayFloat.rows()
        val col = FloatArray(h)
        val colMat = roiGrayFloat.col(scanlineCol.coerceIn(0, roiGrayFloat.cols() - 1))
        colMat.get(0, 0, col)

        buffer.addLast(col)
        while (buffer.size > windowFrames) buffer.pollFirst()

        if (buffer.size < windowFrames) {
            lastResult = lastResult.copy(bufferFill = buffer.size)
            return lastResult
        }

        val T = windowFrames
        val cols = buffer.toTypedArray()  // T entries, each a FloatArray of length h

        // Build (h x T) stack, row-major, mean-subtracted per row -- all in plain Kotlin.
        val stackData = FloatArray(h * T)
        for (y in 0 until h) {
            var rowMean = 0.0
            for (t in 0 until T) rowMean += cols[t][y]
            rowMean /= T
            for (t in 0 until T) stackData[y * T + t] = (cols[t][y] - rowMean).toFloat()
        }

        val stackMat = Mat(h, T, CvType.CV_32F)
        stackMat.put(0, 0, stackData)

        val complexOut = Mat()
        Core.dft(stackMat, complexOut, Core.DFT_ROWS or Core.DFT_COMPLEX_OUTPUT)

        // complexOut is h x T, 2-channel (real, imag). Bulk-read it out.
        val complexData = FloatArray(h * T * 2)
        complexOut.get(0, 0, complexData)

        val nyquistBins = T / 2 + 1  // unique bins, same convention as numpy's rfft
        val meanPower = DoubleArray(nyquistBins)
        for (k in 0 until nyquistBins) {
            var sumPower = 0.0
            for (y in 0 until h) {
                val base = (y * T + k) * 2
                val re = complexData[base]
                val im = complexData[base + 1]
                sumPower += (re * re + im * im).toDouble()
            }
            meanPower[k] = sumPower / h
        }

        var totalEnergy = 0.0
        var dominantFreq = 0.0
        var maxPower = -1.0
        var centroidNumerator = 0.0
        for (k in 1 until nyquistBins) {  // exclude DC bin 0, matching desktop
            val freq = k * fps / T
            val p = meanPower[k]
            totalEnergy += p
            if (p > maxPower) { maxPower = p; dominantFreq = freq }
            centroidNumerator += freq * p
        }
        val centroid = if (totalEnergy > 0) centroidNumerator / totalEnergy else 0.0

        stackMat.release(); complexOut.release()

        lastResult = Result(dominantFreq, totalEnergy, centroid, buffer.size)
        return lastResult
    }
}
