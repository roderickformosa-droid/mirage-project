package com.mirage.app.analysis

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.mirage.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Very-low-volume Google Cloud Vision fallback for uncertain MOVING wind cues.
 *
 * Guardrails:
 * - only invoked by FrameAnalyzer when local recognition is missing/weak
 * - 60 s minimum between requests
 * - hard cap 30 requests/day
 * - hard cap 900 requests/month (keeps well below the first 1,000 monthly units)
 * - counters persist in SharedPreferences across app restarts
 * - sends only a small JPEG crop around the moving candidate, never continuous video
 * - disabled automatically if no CLOUD_VISION_API_KEY was supplied at build time
 *
 * One request uses LABEL_DETECTION only, therefore one Vision feature/unit per request.
 */
class CloudVisionClassifier(context: Context) {
    data class Prediction(
        val label: String,
        val confidence: Double,
        val timestampMs: Long,
        val source: String = "CLOUD"
    )

    data class Quota(
        val sessionUsed: Int,
        val dayUsed: Int,
        val dayLimit: Int,
        val monthUsed: Int,
        val monthLimit: Int
    )

    companion object {
        const val DAILY_LIMIT = 30
        const val MONTHLY_LIMIT = 900
        private const val MIN_REQUEST_INTERVAL_MS = 60_000L
        private val sessionUsed = AtomicInteger(0)
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("cloud_vision_quota", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var lastPrediction: Prediction? = null
    private var lastRequestElapsedMs = 0L

    fun isConfigured(): Boolean = BuildConfig.CLOUD_VISION_API_KEY.isNotBlank()

    fun latest(maxAgeMs: Long = 15_000L): Prediction? =
        lastPrediction?.takeIf { SystemClock.elapsedRealtime() - it.timestampMs <= maxAgeMs }

    fun quota(): Quota {
        rolloverIfNeeded()
        return Quota(
            sessionUsed.get(),
            prefs.getInt("day_used", 0), DAILY_LIMIT,
            prefs.getInt("month_used", 0), MONTHLY_LIMIT
        )
    }

    /**
     * Submit an uncertain moving cue. Returns immediately; result becomes available through latest().
     */
    fun submitIfAllowed(gray: Mat, cue: MotionCue, localConfidence: Double) {
        if (!isConfigured() || gray.empty() || busy.get()) return
        // Cloud is a fallback, not a routine classifier.
        if (localConfidence >= 0.72) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastRequestElapsedMs < MIN_REQUEST_INTERVAL_MS) return
        rolloverIfNeeded()
        if (prefs.getInt("day_used", 0) >= DAILY_LIMIT) return
        if (prefs.getInt("month_used", 0) >= MONTHLY_LIMIT) return

        val crop = cropCue(gray, cue) ?: return
        lastRequestElapsedMs = now
        busy.set(true)
        incrementQuotaBeforeNetworkCall()
        executor.execute {
            try {
                val pred = requestLabels(crop)
                if (pred != null) lastPrediction = pred
            } catch (_: Throwable) {
                // Intentionally silent in field UI; a cloud miss must never stop local analysis.
            } finally {
                crop.recycle()
                busy.set(false)
            }
        }
    }

    private fun cropCue(gray: Mat, cue: MotionCue): Bitmap? {
        val pad = 28
        val x = (cue.leftPx - pad).coerceAtLeast(0)
        val y = (cue.topPx - pad).coerceAtLeast(0)
        val r = (cue.rightPx + pad).coerceAtMost(gray.cols())
        val b = (cue.bottomPx + pad).coerceAtMost(gray.rows())
        if (r - x < 24 || b - y < 24) return null
        val crop = Mat(gray, Rect(x, y, r - x, b - y)).clone()
        val resized = Mat()
        val targetW = 320.0
        val targetH = (crop.rows().toDouble() * targetW / crop.cols().coerceAtLeast(1)).coerceIn(120.0, 480.0)
        Imgproc.resize(crop, resized, Size(targetW, targetH))
        val rgba = Mat()
        Imgproc.cvtColor(resized, rgba, Imgproc.COLOR_GRAY2RGBA)
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        crop.release(); resized.release(); rgba.release()
        return bitmap
    }

    private fun requestLabels(bitmap: Bitmap): Prediction? {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 78, bos)
        val image64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val request = JSONObject().put("requests", JSONArray().put(
            JSONObject()
                .put("image", JSONObject().put("content", image64))
                .put("features", JSONArray().put(
                    JSONObject().put("type", "LABEL_DETECTION").put("maxResults", 8)
                ))
        ))

        val url = URL("https://vision.googleapis.com/v1/images:annotate?key=${BuildConfig.CLOUD_VISION_API_KEY}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) {
            conn.errorStream?.close(); conn.disconnect(); return null
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val annotations = JSONObject(body)
            .optJSONArray("responses")?.optJSONObject(0)
            ?.optJSONArray("labelAnnotations") ?: return null

        var bestLabel: String? = null
        var bestScore = 0.0
        for (i in 0 until annotations.length()) {
            val o = annotations.optJSONObject(i) ?: continue
            val normalized = normalize(o.optString("description")) ?: continue
            val score = o.optDouble("score", 0.0)
            if (score > bestScore) { bestScore = score; bestLabel = normalized }
        }
        return bestLabel?.let { Prediction(it, bestScore, SystemClock.elapsedRealtime()) }
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
            "leaf" in s || "foliage" in s || "vegetation" in s || "plant" in s || "shrub" in s -> "FOLIAGE"
            "flag" in s || "banner" in s || "pennant" in s || "windsock" in s || "wind sock" in s -> "FLAG"
            "wind vane" in s || "weather vane" in s -> "WIND INDICATOR"
            "laundry" in s || "clothesline" in s || "towel" in s || "sheet" in s || "sail" in s -> "FABRIC"
            "rope" in s || "cord" in s || "string" in s || "cable" in s -> "ROPE / LINE"
            "smoke" in s -> "SMOKE"
            "dust" in s || "sand" in s -> "DUST / DEBRIS"
            "mist" in s || "fog" in s -> "FOG / MIST"
            "fabric" in s || "textile" in s || "cloth" in s || "clothing" in s -> "FABRIC"
            "door" in s -> "DOOR"
            "sign" in s -> "SIGN"
            // Cloud calls are reserved for environmental wind cues, not target/object identification.
            "metal" in s || "steel" in s || "person" in s || "human" in s -> null
            "vehicle" in s || "car" in s || "truck" in s || "building" in s || "wall" in s -> null
            else -> null
        }
    }

    private fun rolloverIfNeeded() {
        val today = LocalDate.now().toString()
        val month = YearMonth.now().toString()
        val e = prefs.edit()
        if (prefs.getString("day_key", "") != today) {
            e.putString("day_key", today).putInt("day_used", 0)
        }
        if (prefs.getString("month_key", "") != month) {
            e.putString("month_key", month).putInt("month_used", 0)
        }
        e.apply()
    }

    private fun incrementQuotaBeforeNetworkCall() {
        sessionUsed.incrementAndGet()
        prefs.edit()
            .putInt("day_used", prefs.getInt("day_used", 0) + 1)
            .putInt("month_used", prefs.getInt("month_used", 0) + 1)
            .apply()
    }
}
