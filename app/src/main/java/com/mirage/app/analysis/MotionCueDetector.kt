package com.mirage.app.analysis

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Lightweight secondary-wind detector.
 *
 * It intentionally does NOT pretend to be a semantic neural-network classifier. It finds coherent
 * motion after stabilization, tracks the strongest regions, and assigns conservative visual labels
 * (FLAG/FABRIC-LIKE, FOLIAGE-LIKE, AIRBORNE OBJECT, MOVING OBJECT) from geometry + motion pattern.
 * Those labels are field-test hypotheses, not guaranteed object recognition.
 *
 * Wind-speed output is also an ESTIMATED RANGE based on visual-cue behaviour. Without a known
 * distance/scale, monocular video cannot directly convert pixels/sec into true m/s. The range is
 * therefore deliberately broad and accompanied by confidence.
 */
class MotionCueDetector {
    private var previous: Mat? = null
    private var previousTimestampNs: Long = 0L
    private val tracks = mutableMapOf<Int, Track>()
    private var nextId = 1

    private data class Track(
        val id: Int,
        var center: Point,
        var angleDeg: Double = 0.0,
        var apparentSpeed: Double = 0.0,
        var stableSinceNs: Long = 0L,
        var lastSeenNs: Long = 0L
    )

    fun reset() {
        previous?.release()
        previous = null
        tracks.clear()
        previousTimestampNs = 0L
    }

    fun process(stabilizedGray: Mat, timestampNs: Long): List<MotionCue> {
        val prev = previous
        if (prev == null || prev.size() != stabilizedGray.size()) {
            reset()
            previous = stabilizedGray.clone()
            previousTimestampNs = timestampNs
            return emptyList()
        }

        val dtSec = ((timestampNs - previousTimestampNs).coerceAtLeast(1L) / 1_000_000_000.0)
            .coerceIn(0.03, 2.0)

        val diff = Mat()
        Core.absdiff(stabilizedGray, prev, diff)
        Imgproc.GaussianBlur(diff, diff, Size(3.0, 3.0), 0.0)

        // Adaptive-ish threshold from mean + a floor. Keeps tiny shimmer from being mistaken for an object.
        val mean = Core.mean(diff).`val`[0]
        val threshold = max(7.0, mean * 1.65)
        val mask = Mat()
        Imgproc.threshold(diff, mask, threshold, 255.0, Imgproc.THRESH_BINARY)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        // Close small gaps so deforming cloth/foliage becomes one usable region.
        // Avoid aggressive opening: it was erasing thin flag edges and twigs.
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.dilate(mask, mask, kernel, Point(-1.0, -1.0), 1)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val frameArea = stabilizedGray.cols().toDouble() * stabilizedGray.rows().toDouble()
        val candidates = contours.mapNotNull { contour ->
            val area = Imgproc.contourArea(contour)
            if (area < frameArea * 0.00018 || area > frameArea * 0.45) return@mapNotNull null
            val rect = Imgproc.boundingRect(contour)
            if (rect.width < 5 || rect.height < 5) return@mapNotNull null
            Candidate(rect, area)
        }.sortedByDescending { it.area }.take(5)

        val now = timestampNs
        val output = mutableListOf<MotionCue>()
        val matched = mutableSetOf<Int>()

        for (c in candidates) {
            val center = Point(c.rect.x + c.rect.width / 2.0, c.rect.y + c.rect.height / 2.0)
            val track = tracks.values
                .filter { it.id !in matched }
                .minByOrNull { hypot(it.center.x - center.x, it.center.y - center.y) }
                ?.takeIf { hypot(it.center.x - center.x, it.center.y - center.y) < max(c.rect.width, c.rect.height) * 1.8 + 35.0 }
                ?: Track(nextId++, center, stableSinceNs = now, lastSeenNs = now).also { tracks[it.id] = it }

            matched += track.id
            val dx = center.x - track.center.x
            val dy = center.y - track.center.y
            val translationSpeed = hypot(dx, dy) / dtSec
            // A flag can flap vigorously while its bounding-box centre barely moves.
            // Treat local deformation/change as motion energy as well as centroid translation.
            val roi = Mat(diff, c.rect)
            val deformation = Core.mean(roi).`val`[0]
            val flagOrientation = estimateFlagFreeEndDirection(roi, c.rect)
            roi.release()
            val deformationSpeed = (deformation / 255.0) * stabilizedGray.cols() * 0.55
            val speed = max(translationSpeed, deformationSpeed)
            // For elongated fabric-like regions, estimate the low-motion attachment side vs the
            // higher-motion free edge. This is more useful than instantaneous flutter vectors.
            val angle = flagOrientation ?: if (translationSpeed > 1.5) vectorToClockAngle(dx, dy) else track.angleDeg

            // Stability = direction and speed remain broadly repeatable. Ignore near-static jitter.
            val angleDelta = angularDistance(angle, track.angleDeg)
            val speedRatio = if (track.apparentSpeed > 1.0) speed / track.apparentSpeed else 1.0
            val consistent = speed > 1.5 && (translationSpeed <= 1.5 || angleDelta < 45.0) && speedRatio in 0.40..2.5
            if (!consistent) track.stableSinceNs = now

            track.center = center
            track.angleDeg = angle
            track.apparentSpeed = speed
            track.lastSeenNs = now

            val stableFor = ((now - track.stableSinceNs).coerceAtLeast(0L) / 1_000_000_000.0)
            val stable = stableFor >= 3.0

            val aspect = c.rect.width.toDouble() / c.rect.height.toDouble().coerceAtLeast(1.0)
            val areaFrac = c.area / frameArea
            val label = classify(aspect, areaFrac, speed, c.rect)
            val (windMin, windMax) = estimateWindRange(label, speed, stabilizedGray.cols(), stable)
            val confidence = estimateConfidence(label, c.rect, speed, stable)

            output += MotionCue(
                label = label,
                leftPx = c.rect.x,
                topPx = c.rect.y,
                rightPx = c.rect.x + c.rect.width,
                bottomPx = c.rect.y + c.rect.height,
                clockDirection = clockLabel(angle),
                clockAngleDeg = angle,
                apparentSpeedPxPerSec = speed,
                estimatedWindMinMps = windMin,
                estimatedWindMaxMps = windMax,
                confidence = confidence,
                stable = stable,
                stableForSec = stableFor
            )
        }

        tracks.entries.removeIf { now - it.value.lastSeenNs > 2_000_000_000L }
        previous?.release()
        previous = stabilizedGray.clone()
        previousTimestampNs = timestampNs
        diff.release(); mask.release(); hierarchy.release(); kernel.release()
        contours.forEach { it.release() }
        return output
    }

    private data class Candidate(val rect: Rect, val area: Double)

    private fun classify(aspect: Double, areaFrac: Double, speed: Double, rect: Rect): String {
        return when {
            // Geometry can suggest fabric, but we do not guess foliage from motion alone anymore.
            (aspect > 1.8 || aspect < 0.56) && areaFrac > 0.0006 -> "FLAG/FABRIC-LIKE"
            areaFrac < 0.006 && speed > 24.0 -> "WIND-SENSITIVE MOTION"
            else -> "WIND-SENSITIVE MOTION"
        }
    }

    /**
     * For an elongated moving patch, the attachment side of a flag/drapery normally moves less
     * than its free edge. Compare motion energy at opposite ends and point toward the freer end.
     * Returns null when the evidence is not asymmetric enough to be useful.
     */
    private fun estimateFlagFreeEndDirection(diffRoi: Mat, rect: Rect): Double? {
        if (diffRoi.empty()) return null
        val aspect = rect.width.toDouble() / rect.height.toDouble().coerceAtLeast(1.0)
        if (aspect in 0.70..1.45) return null
        return if (aspect > 1.45 && diffRoi.cols() >= 8) {
            val q = max(2, diffRoi.cols() / 4)
            val left = Mat(diffRoi, Rect(0, 0, q, diffRoi.rows()))
            val right = Mat(diffRoi, Rect(diffRoi.cols() - q, 0, q, diffRoi.rows()))
            val lm = Core.mean(left).`val`[0]; val rm = Core.mean(right).`val`[0]
            left.release(); right.release()
            val ratio = max(lm, rm) / max(1.0, min(lm, rm))
            if (ratio < 1.14) null else if (rm > lm) 90.0 else 270.0
        } else if (diffRoi.rows() >= 8) {
            val q = max(2, diffRoi.rows() / 4)
            val top = Mat(diffRoi, Rect(0, 0, diffRoi.cols(), q))
            val bottom = Mat(diffRoi, Rect(0, diffRoi.rows() - q, diffRoi.cols(), q))
            val tm = Core.mean(top).`val`[0]; val bm = Core.mean(bottom).`val`[0]
            top.release(); bottom.release()
            val ratio = max(tm, bm) / max(1.0, min(tm, bm))
            if (ratio < 1.14) null else if (bm > tm) 180.0 else 0.0
        } else null
    }

    private fun estimateWindRange(label: String, speedPxPerSec: Double, frameWidth: Int, stable: Boolean): Pair<Double, Double> {
        // Normalize apparent motion by frame width so digital resolution changes do not dominate.
        val normPerSec = speedPxPerSec / frameWidth.toDouble().coerceAtLeast(1.0)
        val visualClass = when {
            normPerSec < 0.010 -> 1
            normPerSec < 0.025 -> 2
            normPerSec < 0.050 -> 3
            normPerSec < 0.090 -> 4
            else -> 5
        }
        // Broad Beaufort-like visual ranges. Flag/fabric gets slightly tighter because it responds readily to wind.
        val ranges = if (label == "FLAG/FABRIC-LIKE") {
            listOf(0.5 to 2.0, 1.5 to 3.5, 3.0 to 5.5, 5.0 to 8.0, 7.0 to 11.0)
        } else {
            listOf(0.0 to 2.5, 1.0 to 4.0, 2.5 to 6.0, 4.0 to 8.5, 6.0 to 12.0)
        }
        val pair = ranges[(visualClass - 1).coerceIn(0, ranges.lastIndex)]
        // Stable evidence deserves no false precision; keep same range, stability raises confidence instead.
        return pair
    }

    private fun estimateConfidence(label: String, rect: Rect, speed: Double, stable: Boolean): Double {
        var c = 0.35
        if (label != "MOVING OBJECT") c += 0.12
        if (rect.width >= 20 && rect.height >= 20) c += 0.10
        if (speed > 5.0) c += 0.08
        if (stable) c += 0.20
        return c.coerceIn(0.20, 0.85)
    }

    companion object {
        fun vectorToClockAngle(dx: Double, dy: Double): Double {
            // Image y grows downward. atan2(dx,-dy) yields 0 at up/12 and 90 at right/3.
            return (Math.toDegrees(atan2(dx, -dy)) + 360.0) % 360.0
        }

        fun clockLabel(angleDeg: Double): String {
            val sector = ((angleDeg + 15.0) / 30.0).toInt() % 12
            val hour = if (sector == 0) 12 else sector
            return "$hour o'clock"
        }

        private fun angularDistance(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return min(d, 360.0 - d)
        }
    }
}
