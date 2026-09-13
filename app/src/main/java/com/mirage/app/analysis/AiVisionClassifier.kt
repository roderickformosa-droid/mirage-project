package com.mirage.app.analysis

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight on-device semantic helper using ML Kit image labeling.
 * It is deliberately throttled so it does not run on every video frame.
 * Labels are treated as hypotheses, never as guaranteed truth.
 */
class AiVisionClassifier {
    data class Prediction(val label: String, val confidence: Double, val timestampMs: Long)

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
    )
    private val busyTarget = AtomicBoolean(false)
    private val busyCue = AtomicBoolean(false)
    @Volatile private var lastTarget: Prediction? = null
    @Volatile private var lastCue: Prediction? = null
    private var lastTargetSubmitMs = 0L
    private var lastCueSubmitMs = 0L

    fun latestTarget(maxAgeMs: Long = 3500L): Prediction? =
        lastTarget?.takeIf { SystemClock.elapsedRealtime() - it.timestampMs <= maxAgeMs }

    fun latestCue(maxAgeMs: Long = 2500L): Prediction? =
        lastCue?.takeIf { SystemClock.elapsedRealtime() - it.timestampMs <= maxAgeMs }

    fun submitTarget(gray: Mat) {
        val now = SystemClock.elapsedRealtime()
        if (busyTarget.get() || now - lastTargetSubmitMs < 900L || gray.empty()) return
        lastTargetSubmitMs = now
        val w = (gray.cols() * 0.30).toInt().coerceAtLeast(32)
        val h = (gray.rows() * 0.30).toInt().coerceAtLeast(32)
        val x = ((gray.cols() - w) / 2).coerceAtLeast(0)
        val y = ((gray.rows() - h) / 2).coerceAtLeast(0)
        val crop = Mat(gray, Rect(x, y, w.coerceAtMost(gray.cols() - x), h.coerceAtMost(gray.rows() - y))).clone()
        classify(crop, busyTarget) { prediction -> if (prediction != null) lastTarget = prediction }
    }

    fun submitCue(gray: Mat, cue: MotionCue?) {
        val q = cue ?: return
        val now = SystemClock.elapsedRealtime()
        if (busyCue.get() || now - lastCueSubmitMs < 1100L || gray.empty()) return
        lastCueSubmitMs = now
        val pad = 18
        val x = (q.leftPx - pad).coerceAtLeast(0)
        val y = (q.topPx - pad).coerceAtLeast(0)
        val r = (q.rightPx + pad).coerceAtMost(gray.cols())
        val b = (q.bottomPx + pad).coerceAtMost(gray.rows())
        if (r - x < 20 || b - y < 20) return
        val crop = Mat(gray, Rect(x, y, r - x, b - y)).clone()
        classify(crop, busyCue) { prediction -> if (prediction != null) lastCue = prediction }
    }

    private fun classify(crop: Mat, busy: AtomicBoolean, onDone: (Prediction?) -> Unit) {
        busy.set(true)
        try {
            val resized = Mat()
            val targetW = 256.0
            val targetH = (crop.rows().toDouble() * (256.0 / crop.cols().coerceAtLeast(1))).coerceIn(96.0, 384.0)
            Imgproc.resize(crop, resized, Size(targetW, targetH))
            val rgba = Mat()
            Imgproc.cvtColor(resized, rgba, Imgproc.COLOR_GRAY2RGBA)
            val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bitmap)
            crop.release(); resized.release(); rgba.release()

            labeler.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { labels ->
                    val best = labels
                        .mapNotNull { l -> normalize(l.text)?.let { it to l.confidence.toDouble() } }
                        .maxByOrNull { it.second }
                    onDone(best?.let { Prediction(it.first, it.second, SystemClock.elapsedRealtime()) })
                }
                .addOnFailureListener { onDone(null) }
                .addOnCompleteListener { busy.set(false) }
        } catch (_: Throwable) {
            crop.release()
            busy.set(false)
            onDone(null)
        }
    }

    private fun normalize(raw: String): String? {
        val s = raw.lowercase()
        return when {
            "tree" in s -> "TREE"
            "branch" in s -> "BRANCH"
            "twig" in s -> "TWIG"
            "grass" in s -> "GRASS"
            "palm" in s && ("frond" in s || "leaf" in s) -> "PALM FROND"
            "palm" in s -> "PALM"
            "reed" in s -> "REED"
            "leaf" in s || "plant" in s || "vegetation" in s || "shrub" in s -> "FOLIAGE"
            "flag" in s || "banner" in s || "pennant" in s || "windsock" in s || "wind sock" in s -> "FLAG / FABRIC"
            "wind vane" in s || "weather vane" in s -> "WIND INDICATOR"
            "laundry" in s || "clothesline" in s || "towel" in s || "sheet" in s || "sail" in s -> "FLAG / FABRIC"
            "fabric" in s || "cloth" in s || "clothing" in s || "curtain" in s || "drape" in s || "drapery" in s -> "FLAG / FABRIC"
            "rope" in s || "cord" in s || "string" in s || "cable" in s -> "ROPE / LINE"
            "smoke" in s -> "SMOKE"
            "fog" in s || "mist" in s -> "FOG / MIST"
            "dust" in s || "sand" in s -> "DUST / DEBRIS"
            "spray" in s || "sea spray" in s -> "SPRAY"
            "door" in s -> "DOOR"
            "road sign" in s || "traffic sign" in s || "sign" in s -> "SIGN"
            // Rigid/irrelevant objects are intentionally ignored: semantic AI exists only to classify wind-sensitive cues.
            "building" in s || "house" in s || "wall" in s || "metal" in s || "steel" in s -> null
            "vehicle" in s || "car" in s || "person" in s || "human" in s -> null
            else -> null
        }
    }
}
