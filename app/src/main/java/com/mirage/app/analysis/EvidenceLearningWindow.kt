package com.mirage.app.analysis

import kotlin.math.max

/** Rolling 10-second measure of useful evidence, not a timer. */
class EvidenceLearningWindow {
    data class Reading(val percent: Int, val useful: Boolean, val usefulSeconds: Double)
    private data class Sample(val t: Long, val score: Double, val useful: Boolean)
    private val samples = ArrayDeque<Sample>()

    fun reset() = samples.clear()

    fun update(nowNs: Long, cueScore: Double, mirageScore: Double, agreement: Double, trackingOk: Boolean): Reading {
        val evidence = max(cueScore, mirageScore).coerceIn(0.0, 1.0)
        val useful = trackingOk && evidence >= 0.28
        val score = if (!trackingOk) 0.0 else (0.62 * evidence + 0.38 * agreement.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        samples.addLast(Sample(nowNs, score, useful))
        val cutoff = nowNs - 10_000_000_000L
        while (samples.isNotEmpty() && samples.first().t < cutoff) samples.removeFirst()
        if (samples.isEmpty()) return Reading(0, false, 0.0)

        val usefulSamples = samples.filter { it.useful }
        if (usefulSamples.isEmpty()) return Reading((samples.map { it.score }.average() * 18.0).toInt().coerceIn(0, 18), false, 0.0)

        val avgQuality = usefulSamples.map { it.score }.average()
        val usefulFraction = usefulSamples.size.toDouble() / samples.size.toDouble()
        val spanSec = ((usefulSamples.last().t - usefulSamples.first().t).coerceAtLeast(0L) / 1_000_000_000.0)
        val persistence = (spanSec / 6.0).coerceIn(0.0, 1.0)
        val pct = ((0.50 * avgQuality + 0.30 * usefulFraction + 0.20 * persistence) * 100.0).toInt().coerceIn(0, 100)
        return Reading(pct, pct >= 35, spanSec)
    }
}
