package com.mirage.app.analysis

import kotlin.math.*

/**
 * Turns frame-by-frame motion blobs into persistent environmental cues.
 * One flag should remain one cue instead of becoming several votes in the fusion engine.
 */
class CuePersistenceTracker {
    private data class Track(
        val id: Int,
        var cx: Double,
        var cy: Double,
        var w: Double,
        var h: Double,
        var lastSeenNs: Long,
        var firstSeenNs: Long,
        var angleX: Double = 0.0,
        var angleY: Double = 0.0,
        var speed: Double = 0.0,
        var confidence: Double = 0.0,
        var label: String = "WIND-SENSITIVE MOTION",
        var labelScore: Double = 0.0,
        var lastCue: MotionCue? = null
    )

    private val tracks = mutableMapOf<Int, Track>()
    private var nextId = 1

    fun reset() = tracks.clear()

    fun update(cues: List<MotionCue>, nowNs: Long): List<MotionCue> {
        val matched = mutableSetOf<Int>()
        val sorted = cues.sortedByDescending { it.confidence }

        for (cue in sorted) {
            val cx = (cue.leftPx + cue.rightPx) / 2.0
            val cy = (cue.topPx + cue.bottomPx) / 2.0
            val w = (cue.rightPx - cue.leftPx).toDouble().coerceAtLeast(1.0)
            val h = (cue.bottomPx - cue.topPx).toDouble().coerceAtLeast(1.0)
            val match = tracks.values
                .filter { it.id !in matched }
                .map { it to hypot(it.cx - cx, it.cy - cy) }
                .filter { (t, d) -> d < max(max(w, h), max(t.w, t.h)) * 1.4 + 28.0 }
                .minByOrNull { it.second }?.first
                ?: Track(nextId++, cx, cy, w, h, nowNs, nowNs).also { tracks[it.id] = it }

            matched += match.id
            val a = Math.toRadians(cue.clockAngleDeg)
            val smooth = 0.24
            match.angleX = (1.0 - smooth) * match.angleX + smooth * sin(a)
            match.angleY = (1.0 - smooth) * match.angleY + smooth * cos(a)
            match.speed = if (match.speed <= 0.0) cue.apparentSpeedPxPerSec else match.speed * 0.72 + cue.apparentSpeedPxPerSec * 0.28
            match.confidence = (match.confidence * 0.72 + cue.confidence * 0.28).coerceIn(0.0, 0.95)
            match.cx = match.cx * 0.70 + cx * 0.30
            match.cy = match.cy * 0.70 + cy * 0.30
            match.w = match.w * 0.75 + w * 0.25
            match.h = match.h * 0.75 + h * 0.25
            match.lastSeenNs = nowNs

            val semantic = semanticPriority(cue.label)
            if (semantic > match.labelScore + 0.05 || clean(cue.label) == clean(match.label)) {
                match.label = clean(cue.label)
                match.labelScore = max(match.labelScore * 0.85, semantic * cue.confidence)
            } else {
                match.labelScore *= 0.97
            }
            match.lastCue = cue
        }

        tracks.entries.removeIf { nowNs - it.value.lastSeenNs > 3_500_000_000L }

        return tracks.values
            .filter { nowNs - it.lastSeenNs <= 1_400_000_000L }
            .mapNotNull { t ->
                val base = t.lastCue ?: return@mapNotNull null
                val angle = (Math.toDegrees(atan2(t.angleX, t.angleY)) + 360.0) % 360.0
                val persistenceSec = (nowNs - t.firstSeenNs).coerceAtLeast(0L) / 1_000_000_000.0
                val persistentBonus = (persistenceSec / 5.0).coerceIn(0.0, 1.0) * 0.18
                val conf = (t.confidence + persistentBonus).coerceIn(0.0, 0.96)
                base.copy(
                    label = t.label,
                    leftPx = (t.cx - t.w / 2).roundToInt(),
                    topPx = (t.cy - t.h / 2).roundToInt(),
                    rightPx = (t.cx + t.w / 2).roundToInt(),
                    bottomPx = (t.cy + t.h / 2).roundToInt(),
                    clockDirection = MotionCueDetector.clockLabel(angle),
                    clockAngleDeg = angle,
                    apparentSpeedPxPerSec = t.speed,
                    confidence = conf,
                    stableForSec = max(base.stableForSec, persistenceSec),
                    stable = base.stable && persistenceSec >= 3.0
                )
            }
            .sortedByDescending { semanticPriority(it.label) * it.confidence }
            .take(4)
    }

    private fun clean(s: String) = s.replace(" [CLOUD]", "")

    private fun semanticPriority(label: String): Double {
        val s = label.uppercase()
        return when {
            "FLAG" in s || "FABRIC" in s || "BANNER" in s || "DRAPERY" in s || "PENNANT" in s || "WINDSOCK" in s || "LAUNDRY" in s || "TOWEL" in s || "SAIL" in s || "WIND INDICATOR" in s -> 1.0
            "SMOKE" in s || "FOG" in s || "MIST" in s -> 0.95
            "GRASS" in s || "REED" in s || "FOLIAGE" in s || "LEAF" in s -> 0.88
            "PALM" in s || "BRANCH" in s || "TWIG" in s -> 0.76
            "ROPE" in s || "LINE" in s -> 0.66
            else -> 0.32
        }
    }
}
