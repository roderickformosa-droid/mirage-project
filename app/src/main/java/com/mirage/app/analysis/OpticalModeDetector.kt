package com.mirage.app.analysis

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Detects whether the phone is looking through a spotting-scope eyepiece or directly at the scene.
 * The detector deliberately uses slow hysteresis so a dark scene cannot make the mode flicker.
 */
class OpticalModeDetector {
    enum class Mode { DETECTING, PHONE, SPOTTING_SCOPE }
    enum class Override { AUTO, PHONE, SPOTTING_SCOPE }

    data class Result(
        val mode: Mode,
        val confidence: Double,
        val usableRect: Rect,
        val changedRecently: Boolean
    )

    private var overrideMode = Override.AUTO
    private var lockedMode = Mode.DETECTING
    private var scopeVotes = 0
    private var phoneVotes = 0
    private var framesSinceChange = 100

    fun setOverride(value: Override) {
        overrideMode = value
        scopeVotes = 0
        phoneVotes = 0
        lockedMode = when (value) {
            Override.PHONE -> Mode.PHONE
            Override.SPOTTING_SCOPE -> Mode.SPOTTING_SCOPE
            Override.AUTO -> Mode.DETECTING
        }
        framesSinceChange = 0
    }

    fun currentOverride(): Override = overrideMode

    fun reset() {
        scopeVotes = 0
        phoneVotes = 0
        lockedMode = when (overrideMode) {
            Override.PHONE -> Mode.PHONE
            Override.SPOTTING_SCOPE -> Mode.SPOTTING_SCOPE
            Override.AUTO -> Mode.DETECTING
        }
        framesSinceChange = 0
    }

    fun detect(gray: Mat): Result {
        if (gray.empty()) return Result(Mode.DETECTING, 0.0, Rect(0, 0, 1, 1), true)
        framesSinceChange++

        if (overrideMode != Override.AUTO) {
            val mode = if (overrideMode == Override.PHONE) Mode.PHONE else Mode.SPOTTING_SCOPE
            return Result(mode, 1.0, usableRect(gray, mode), framesSinceChange < 8)
        }

        val w = gray.cols(); val h = gray.rows()
        val patchW = max(12, (w * 0.16).toInt())
        val patchH = max(12, (h * 0.16).toInt())
        val centerW = max(24, (w * 0.36).toInt())
        val centerH = max(24, (h * 0.36).toInt())
        val cx = (w - centerW) / 2; val cy = (h - centerH) / 2

        fun mean(r: Rect): Double {
            val m = Mat(gray, r)
            val v = Core.mean(m).`val`[0]
            m.release()
            return v
        }

        val center = mean(Rect(cx, cy, centerW, centerH))
        val corners = listOf(
            mean(Rect(0, 0, patchW, patchH)),
            mean(Rect(w - patchW, 0, patchW, patchH)),
            mean(Rect(0, h - patchH, patchW, patchH)),
            mean(Rect(w - patchW, h - patchH, patchW, patchH))
        )
        val darkThreshold = min(42.0, center * 0.42)
        val darkCorners = corners.count { it < darkThreshold }
        val cornerMean = corners.average()
        val contrast = ((center - cornerMean) / max(30.0, center)).coerceIn(0.0, 1.0)
        val scopeLike = center > 38.0 && darkCorners >= 3 && contrast >= 0.42

        if (scopeLike) {
            scopeVotes = (scopeVotes + 1).coerceAtMost(12)
            phoneVotes = (phoneVotes - 1).coerceAtLeast(0)
        } else {
            phoneVotes = (phoneVotes + 1).coerceAtMost(12)
            scopeVotes = (scopeVotes - 1).coerceAtLeast(0)
        }

        val old = lockedMode
        lockedMode = when {
            scopeVotes >= 7 -> Mode.SPOTTING_SCOPE
            phoneVotes >= 7 -> Mode.PHONE
            else -> lockedMode
        }
        if (lockedMode != old) framesSinceChange = 0

        val confidence = when (lockedMode) {
            Mode.SPOTTING_SCOPE -> (scopeVotes / 10.0).coerceIn(0.0, 1.0)
            Mode.PHONE -> (phoneVotes / 10.0).coerceIn(0.0, 1.0)
            Mode.DETECTING -> max(scopeVotes, phoneVotes) / 10.0
        }
        return Result(lockedMode, confidence, usableRect(gray, lockedMode), framesSinceChange < 8)
    }

    private fun usableRect(gray: Mat, mode: Mode): Rect {
        val w = gray.cols(); val h = gray.rows()
        if (mode != Mode.SPOTTING_SCOPE) return Rect(0, 0, w, h)
        // Conservative square inside the circular scope image; avoids the black eyepiece border.
        val side = (min(w, h) * 0.78).toInt().coerceAtLeast(80).coerceAtMost(min(w, h))
        return Rect((w - side) / 2, (h - side) / 2, side, side)
    }
}
