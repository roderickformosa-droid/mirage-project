package com.mirage.app.analysis

import kotlin.math.*

/**
 * Converts apparent image-plane motion into a range-assisted physical-motion estimate when a
 * usable distance is available. This is intentionally conservative: object motion is not the
 * same thing as air speed, especially for flags and foliage, so the final wind output remains a
 * range with an explicit source/quality label.
 */
object RangeEstimator {

    enum class AutoPlane { GROUND, SEA_LEVEL }

    data class Context(
        val manualRangeM: Double?,
        val autoRangeEnabled: Boolean,
        val cameraPitchDeg: Double?,
        val gpsAltitudeM: Double?,
        val cameraHeightM: Double,
        val autoPlane: AutoPlane,
        /** Effective spotting-scope horizontal FOV before phone digital zoom. */
        val scopeHorizontalFovDeg: Double,
        val phoneZoomRatio: Double
    )

    data class DistanceEstimate(
        val meters: Double?,
        val source: String,
        val confidence: Double
    )

    fun distance(ctx: Context): DistanceEstimate {
        ctx.manualRangeM?.takeIf { it.isFinite() && it in 1.0..100_000.0 }?.let {
            return DistanceEstimate(it, "KNOWN RANGE", 0.95)
        }
        if (!ctx.autoRangeEnabled) return DistanceEstimate(null, "UNKNOWN", 0.0)

        val pitch = ctx.cameraPitchDeg ?: return DistanceEstimate(null, "NO PITCH", 0.0)
        // Geometry only becomes useful when the camera is meaningfully below horizontal.
        // Close to the horizon, a tiny pitch error creates a huge range error.
        if (pitch > -1.5) return DistanceEstimate(null, "TOO LEVEL", 0.0)

        val height = when (ctx.autoPlane) {
            AutoPlane.SEA_LEVEL -> ctx.gpsAltitudeM?.takeIf { it.isFinite() && it > 1.0 }
            AutoPlane.GROUND -> ctx.cameraHeightM.takeIf { it.isFinite() && it > 0.2 }
        } ?: return DistanceEstimate(null, "NO HEIGHT", 0.0)

        val depressionRad = Math.toRadians(abs(pitch))
        val horizontal = height / tan(depressionRad).coerceAtLeast(1e-6)
        val slant = hypot(horizontal, height)
        if (!slant.isFinite() || slant !in 1.0..100_000.0) return DistanceEstimate(null, "UNRELIABLE", 0.0)

        val pitchConfidence = ((abs(pitch) - 1.5) / 8.0).coerceIn(0.0, 1.0)
        val source = if (ctx.autoPlane == AutoPlane.SEA_LEVEL) "GPS+PITCH/SEA" else "PITCH/GROUND"
        val base = if (ctx.autoPlane == AutoPlane.SEA_LEVEL) 0.48 else 0.40
        return DistanceEstimate(slant, source, (base + 0.22 * pitchConfidence).coerceAtMost(0.70))
    }

    fun enrich(cue: MotionCue, frameWidthPx: Int, ctx: Context): MotionCue {
        val d = distance(ctx)
        val distanceM = d.meters ?: return cue.copy(
            distanceM = null,
            distanceSource = d.source,
            physicalMotionMps = null,
            windEstimateQuality = "VISUAL ONLY"
        )

        // The scope's FOV is the important calibration. Phone digital zoom crops that field.
        val effectiveFovDeg = (ctx.scopeHorizontalFovDeg / ctx.phoneZoomRatio.coerceAtLeast(1.0))
            .coerceIn(0.05, 20.0)
        val angularRateRadSec = (cue.apparentSpeedPxPerSec / frameWidthPx.toDouble().coerceAtLeast(1.0)) *
            Math.toRadians(effectiveFovDeg)
        val transverseMps = distanceM * angularRateRadSec

        if (!transverseMps.isFinite() || transverseMps < 0.0 || transverseMps > 150.0) {
            return cue.copy(
                distanceM = distanceM,
                distanceSource = d.source,
                physicalMotionMps = null,
                windEstimateQuality = "RANGE FOUND / MOTION UNRELIABLE"
            )
        }

        val multiplier = when (cue.label) {
            "AIRBORNE OBJECT" -> 0.70 to 1.45
            "FLAG/FABRIC-LIKE" -> 0.75 to 2.40
            "FOLIAGE-LIKE" -> 0.90 to 3.00
            else -> 0.55 to 2.20
        }
        val physicalWindLow = transverseMps * multiplier.first
        val physicalWindHigh = transverseMps * multiplier.second

        // Blend physical-motion evidence with the visual prior. Keep the interval broad on purpose.
        val low = (0.65 * physicalWindLow + 0.35 * cue.estimatedWindMinMps).coerceAtLeast(0.0)
        val high = (0.65 * physicalWindHigh + 0.35 * cue.estimatedWindMaxMps).coerceAtLeast(low + 0.2)
        val boostedConfidence = (cue.confidence + 0.18 * d.confidence).coerceAtMost(0.92)

        return cue.copy(
            estimatedWindMinMps = low,
            estimatedWindMaxMps = high,
            confidence = boostedConfidence,
            distanceM = distanceM,
            distanceSource = d.source,
            physicalMotionMps = transverseMps,
            windEstimateQuality = if (d.confidence >= 0.8) "RANGE-ASSISTED HIGH" else "RANGE-ASSISTED"
        )
    }
}
