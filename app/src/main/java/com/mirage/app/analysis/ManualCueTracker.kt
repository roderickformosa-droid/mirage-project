package com.mirage.app.analysis

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.*

/**
 * User-guided tracker for TRAIN mode. The user tells the app what the cue is and taps it once.
 * From that point the app measures motion inside that persistent region directly, without waiting
 * for generic scene-wide motion detection or semantic classification to succeed first.
 */
class ManualCueTracker {
    private var label: String? = null
    private var nx = 0.5
    private var ny = 0.5
    private var previous: Mat? = null
    private var previousTimestampNs = 0L
    private var stableSinceNs = 0L
    private var lastAngleDeg = Double.NaN
    private var lastSpeedPxSec = Double.NaN

    fun active(): Boolean = label != null
    fun activeLabel(): String? = label

    fun lock(label: String, normalizedX: Double, normalizedY: Double) {
        this.label = label.uppercase()
        nx = normalizedX.coerceIn(0.0, 1.0)
        ny = normalizedY.coerceIn(0.0, 1.0)
        resetHistory()
    }

    fun clear() {
        label = null
        resetHistory()
    }

    fun resetHistory() {
        previous?.release(); previous = null
        previousTimestampNs = 0L
        stableSinceNs = 0L
        lastAngleDeg = Double.NaN
        lastSpeedPxSec = Double.NaN
    }

    fun process(gray: Mat, timestampNs: Long): MotionCue? {
        val cueLabel = label ?: return null
        if (gray.empty()) return null
        val roi = selectionRect(gray.cols(), gray.rows(), cueLabel)
        val current = Mat(gray, roi).clone()
        Imgproc.GaussianBlur(current, current, org.opencv.core.Size(3.0, 3.0), 0.0)

        val prev = previous
        if (prev == null || prev.size() != current.size()) {
            previous?.release()
            previous = current.clone()
            previousTimestampNs = timestampNs
            current.release()
            return null
        }

        val dt = ((timestampNs - previousTimestampNs).coerceAtLeast(1L) / 1_000_000_000.0).coerceIn(0.03, 1.0)
        val flow = Mat()
        Video.calcOpticalFlowFarneback(prev, current, flow, 0.5, 3, 15, 3, 5, 1.1, 0)
        val channels = mutableListOf<Mat>()
        Core.split(flow, channels)
        val fx = channels[0]
        val fy = channels[1]
        val mag = Mat()
        Core.magnitude(fx, fy, mag)

        val mask = Mat()
        Imgproc.threshold(mag, mask, 0.18, 255.0, Imgproc.THRESH_BINARY)
        mask.convertTo(mask, CvType.CV_8UC1)
        val activePixels = Core.countNonZero(mask)
        val totalPixels = (mask.rows() * mask.cols()).coerceAtLeast(1)
        val activeFraction = activePixels.toDouble() / totalPixels.toDouble()

        val meanFx = if (activePixels > 0) Core.mean(fx, mask).`val`[0] else 0.0
        val meanFy = if (activePixels > 0) Core.mean(fy, mask).`val`[0] else 0.0
        val meanMagPerFrame = if (activePixels > 0) Core.mean(mag, mask).`val`[0] else 0.0
        val speedPxSec = meanMagPerFrame / dt
        val angle = if (hypot(meanFx, meanFy) >= 0.10) MotionCueDetector.vectorToClockAngle(meanFx, meanFy) else lastAngleDeg
        val usableMotion = activeFraction >= 0.003 && speedPxSec >= 0.8 && angle.isFinite()

        if (stableSinceNs == 0L) stableSinceNs = timestampNs
        if (usableMotion) {
            val angleOk = !lastAngleDeg.isFinite() || angularDistance(angle, lastAngleDeg) <= 55.0
            val speedOk = !lastSpeedPxSec.isFinite() || lastSpeedPxSec < 0.5 || speedPxSec / lastSpeedPxSec.coerceAtLeast(0.5) in 0.30..3.2
            if (!angleOk || !speedOk) stableSinceNs = timestampNs
            lastAngleDeg = angle
            lastSpeedPxSec = speedPxSec
        } else {
            stableSinceNs = timestampNs
        }

        val stableFor = ((timestampNs - stableSinceNs).coerceAtLeast(0L) / 1_000_000_000.0)
        val stable = usableMotion && stableFor >= 3.0
        val (minWind, maxWind) = estimateWindRange(cueLabel, speedPxSec, gray.cols(), usableMotion)
        val confidence = when {
            !usableMotion -> 0.18
            activeFraction > 0.08 -> 0.80
            activeFraction > 0.025 -> 0.68
            else -> 0.55
        } + if (stable) 0.10 else 0.0

        previous?.release()
        previous = current.clone()
        previousTimestampNs = timestampNs
        current.release(); flow.release(); mag.release(); mask.release(); channels.forEach { it.release() }

        if (!usableMotion) return null
        return MotionCue(
            label = "$cueLabel [USER LOCK]",
            leftPx = roi.x,
            topPx = roi.y,
            rightPx = roi.x + roi.width,
            bottomPx = roi.y + roi.height,
            clockDirection = MotionCueDetector.clockLabel(angle),
            clockAngleDeg = angle,
            apparentSpeedPxPerSec = speedPxSec,
            estimatedWindMinMps = minWind,
            estimatedWindMaxMps = maxWind,
            confidence = confidence.coerceIn(0.20, 0.95),
            stable = stable,
            stableForSec = stableFor,
            windEstimateQuality = "USER-LOCKED VISUAL CUE"
        )
    }

    private fun selectionRect(w: Int, h: Int, cueLabel: String): Rect {
        val fracW = when (cueLabel) {
            "FOLIAGE" -> 0.44
            "MIRAGE" -> 0.50
            else -> 0.36
        }
        val fracH = when (cueLabel) {
            "FOLIAGE" -> 0.40
            "MIRAGE" -> 0.48
            else -> 0.32
        }
        val rw = (w * fracW).toInt().coerceIn(80, w)
        val rh = (h * fracH).toInt().coerceIn(70, h)
        val cx = (nx * w).toInt()
        val cy = (ny * h).toInt()
        val x = (cx - rw / 2).coerceIn(0, max(0, w - rw))
        val y = (cy - rh / 2).coerceIn(0, max(0, h - rh))
        return Rect(x, y, rw, rh)
    }

    private fun estimateWindRange(label: String, speedPxSec: Double, frameWidth: Int, usable: Boolean): Pair<Double, Double> {
        if (!usable) return 0.0 to 0.0
        val norm = speedPxSec / frameWidth.toDouble().coerceAtLeast(1.0)
        val mph = when {
            norm < 0.010 -> 1.0 to 3.0
            norm < 0.025 -> 2.0 to 4.0
            norm < 0.050 -> 4.0 to 6.0
            norm < 0.090 -> 7.0 to 9.0
            else -> 12.0 to 14.0
        }
        val factor = 0.44704
        return mph.first * factor to mph.second * factor
    }

    private fun angularDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return min(d, 360.0 - d)
    }
}
