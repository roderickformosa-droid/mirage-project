package com.mirage.app.analysis

import kotlin.math.*

/**
 * Combines mirage and multiple visible environmental cues into one conservative opinion.
 * Recognition tells us WHAT is moving; motion analysis tells us HOW it is moving.
 * The engine prefers "INSUFFICIENT" over inventing certainty.
 */
class WindFusionEngine {
    data class Fusion(
        val state: String,
        val clockDirection: String,
        val confidence: Double,
        val sources: String,
        val windMinMps: Double,
        val windMaxMps: Double,
        val stableForSec: Double,
        val directionAgreement: Double
    )

    private var stableSinceNs = 0L
    private var previousDirection = Double.NaN

    fun update(
        mirageDetected: Boolean,
        mirageStable: Boolean,
        mirageClockAngleDeg: Double,
        mirageSignalScore: Double,
        cues: List<MotionCue>,
        nowNs: Long
    ): Fusion {
        data class Evidence(
            val source: String,
            val angle: Double,
            val weight: Double,
            val stable: Boolean,
            val minWind: Double?,
            val maxWind: Double?
        )

        val evidence = mutableListOf<Evidence>()
        if (mirageDetected && mirageClockAngleDeg.isFinite()) {
            // Mirage contributes a deliberately broad strength bracket. Signal strength is NOT a direct anemometer;
            // this only prevents false precision while still giving the field UI a usable estimated range.
            val m = mirageSignalScore.coerceIn(0.0, 1.0)
            val mirageMin = when { m < 0.35 -> 0.5; m < 0.60 -> 1.0; m < 0.80 -> 2.0; else -> 3.0 }
            val mirageMax = when { m < 0.35 -> 3.0; m < 0.60 -> 5.0; m < 0.80 -> 7.0; else -> 9.0 }
            evidence += Evidence(
                "MIRAGE", mirageClockAngleDeg,
                (0.48 + 0.32 * m),
                mirageStable, mirageMin, mirageMax
            )
        }

        cues.sortedByDescending { cueWeight(it) * it.confidence }.take(5).forEach { q ->
            val semantic = cueSemanticWeight(q.label)
            val w = semantic * q.confidence.coerceIn(0.0, 1.0)
            if (w >= 0.12 && q.clockAngleDeg.isFinite()) {
                val speedEligible = semantic >= 0.58
                evidence += Evidence(
                    cleanLabel(q.label), q.clockAngleDeg, w, q.stable,
                    if (speedEligible) q.estimatedWindMinMps else null,
                    if (speedEligible) q.estimatedWindMaxMps else null
                )
            }
        }

        if (evidence.isEmpty()) {
            stableSinceNs = nowNs
            previousDirection = Double.NaN
            return Fusion("INSUFFICIENT", "--", 0.0, "NONE", 0.0, 0.0, 0.0, 0.0)
        }

        val totalW = evidence.sumOf { it.weight }.coerceAtLeast(1e-6)
        val sx = evidence.sumOf { sin(Math.toRadians(it.angle)) * it.weight }
        val sy = evidence.sumOf { cos(Math.toRadians(it.angle)) * it.weight }
        val meanAngle = (Math.toDegrees(atan2(sx, sy)) + 360.0) % 360.0
        val resultant = hypot(sx, sy) / totalW
        val stableFraction = evidence.sumOf { if (it.stable) it.weight else 0.0 } / totalW
        val sourceDiversity = evidence.map { it.source }.distinct().size

        // A second independent type of cue increases trust, but never forces a result.
        val diversityBonus = ((sourceDiversity - 1).coerceAtLeast(0) * 0.08).coerceAtMost(0.20)
        var confidence = (0.42 * resultant + 0.34 * stableFraction + 0.24 * min(1.0, totalW / 1.8) + diversityBonus)
            .coerceIn(0.0, 0.96)

        // Direction must stay repeatable for a stable condition.
        val directionRepeatable = !previousDirection.isFinite() || angularDistance(previousDirection, meanAngle) <= 25.0
        val enoughEvidence = totalW >= 0.42
        val coherent = resultant >= 0.58
        val stableNow = enoughEvidence && coherent && stableFraction >= 0.58 && directionRepeatable
        if (!stableNow) stableSinceNs = nowNs
        if (stableSinceNs == 0L) stableSinceNs = nowNs
        val stableFor = (nowNs - stableSinceNs).coerceAtLeast(0L) / 1_000_000_000.0
        previousDirection = meanAngle

        val state = when {
            !enoughEvidence || confidence < 0.38 -> "INSUFFICIENT"
            stableNow && stableFor >= 3.0 && confidence >= 0.58 -> "STABLE"
            else -> "CHANGING"
        }
        if (state == "INSUFFICIENT") confidence *= 0.7

        val windEvidence = evidence.filter { it.minWind != null && it.maxWind != null }
        val windW = windEvidence.sumOf { it.weight }.coerceAtLeast(1e-6)
        val windMin = if (windEvidence.isEmpty()) 0.0 else windEvidence.sumOf { it.minWind!! * it.weight } / windW
        val windMax = if (windEvidence.isEmpty()) 0.0 else windEvidence.sumOf { it.maxWind!! * it.weight } / windW

        val sourceText = evidence
            .sortedByDescending { it.weight }
            .map { it.source }
            .distinct()
            .take(4)
            .joinToString(" + ")

        return Fusion(
            state = state,
            clockDirection = MotionCueDetector.clockLabel(meanAngle),
            confidence = confidence,
            sources = sourceText,
            windMinMps = windMin,
            windMaxMps = windMax,
            stableForSec = if (state == "STABLE") stableFor else 0.0,
            directionAgreement = resultant
        )
    }

    private fun cueWeight(q: MotionCue): Double = cueSemanticWeight(q.label) * q.confidence

    /** Lighter/free material responds sooner; heavy vegetation requires stronger movement to carry equal weight. */
    private fun cueSemanticWeight(label: String): Double {
        val s = label.uppercase()
        return when {
            "SMOKE" in s || "FOG" in s || "MIST" in s || "DUST" in s -> 1.00
            "FLAG" in s || "FABRIC" in s || "RIBBON" in s || "PENNANT" in s || "WINDSOCK" in s || "LAUNDRY" in s || "TOWEL" in s || "SAIL" in s || "WIND INDICATOR" in s -> 0.95
            "GRASS" in s || "LEAF" in s || "FOLIAGE" in s -> 0.82
            "TWIG" in s || "REED" in s || "ROPE" in s || "LINE" in s -> 0.72
            "BRANCH" in s || "PALM FROND" in s -> 0.58
            "TREE" in s || "PALM" in s -> 0.38
            "AIRBORNE" in s -> 0.32
            else -> 0.18
        }
    }

    private fun cleanLabel(label: String): String = label.replace(" [CLOUD]", "")

    private fun angularDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return min(d, 360.0 - d)
    }
}
