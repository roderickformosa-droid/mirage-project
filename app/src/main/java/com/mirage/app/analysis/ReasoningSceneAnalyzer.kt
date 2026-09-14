package com.mirage.app.analysis

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.mirage.app.BuildConfig
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v0.75 cloud reasoning supervisor.
 *
 * This is deliberately observational: it identifies environmental wind cues, ranks their
 * sensitivity, and judges whether the visible environment appears stable/changing/insufficient.
 * It does NOT provide ballistic corrections, holds, firing commands, or trajectory solutions.
 *
 * Continuous pixel measurement remains on-device. The reasoning supervisor samples the scene
 * periodically so it can re-evaluate what is worth watching as the scene changes.
 */
class ReasoningSceneAnalyzer(private val context: Context) {
    data class Cue(
        val label: String,
        val sensitivity: String,
        val location: String,
        val observation: String
    )
    data class Assessment(
        val createdMs: Long,
        val state: String,
        val confidence: Double,
        val summary: String,
        val direction: String,
        val speedBandMph: String,
        val cues: List<Cue>,
        val rationale: String
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var latest: Assessment? = null
    @Volatile private var lastSubmitMs = 0L

    fun configured(): Boolean = BuildConfig.OPENAI_API_KEY.isNotBlank()
    fun latest(maxAgeMs: Long = 30_000L): Assessment? = latest?.takeIf { SystemClock.elapsedRealtime() - it.createdMs <= maxAgeMs }

    fun submitIfDue(gray: Mat, opticalMode: String, rangeM: Double? = null, minIntervalMs: Long = 8_000L) {
        if (!configured() || gray.empty() || busy.get()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSubmitMs < minIntervalMs) return
        lastSubmitMs = now

        val jpg = MatOfByte()
        if (!Imgcodecs.imencode(".jpg", gray, jpg)) { jpg.release(); return }
        val bytes = jpg.toArray(); jpg.release()
        if (!busy.compareAndSet(false, true)) return

        executor.execute {
            try { latest = callReasoner(bytes, opticalMode, rangeM) }
            catch (t: Throwable) { Log.w("MirageReasoner", "Reasoning call failed", t) }
            finally { busy.set(false) }
        }
    }

    private fun callReasoner(jpg: ByteArray, opticalMode: String, rangeM: Double?): Assessment {
        val image64 = Base64.encodeToString(jpg, Base64.NO_WRAP)
        val rangeText = rangeM?.let { "Known viewing range: %.0f m.".format(it) } ?: "Viewing range not supplied."
        val prompt = """
You are the environmental-observation supervisor for Mirage. Examine this single live camera frame as visual evidence only.
Optical mode: $opticalMode. $rangeText
Your task is to identify visible environmental cues that could reveal wind movement or stability: flags/fabric, smoke/dust/mist, flexible foliage, grass/reeds, thin twigs/branch tips, pennants/lines, or visible mirage/refraction. Prefer the most wind-sensitive parts of objects (for example leaf or palm-frond tips rather than a rigid trunk). Ignore objects too rigid or too visually ambiguous to be useful. A soft target/scene is acceptable in spotting-scope mode because the user may focus into the air mass for mirage.
Do not give firing advice, holds, corrections, aiming instructions, or a command to shoot. Do not infer a shot result.
From one still image you may identify cue geometry, but do not pretend to observe temporal movement that requires video. If motion/stability cannot be known from this frame, say INSUFFICIENT and let the on-device temporal tracker decide over time.
Return ONLY valid JSON with exactly these keys:
{"state":"STABLE|CHANGING|INSUFFICIENT","confidence":0.0,"summary":"short scene summary","direction":"visual direction if supported, else UNKNOWN","speed_band_mph":"broad visual estimate only if genuinely supported, else UNKNOWN","rationale":"short explanation","cues":[{"label":"FLAG","sensitivity":"HIGH|MEDIUM|LOW","location":"brief normalized/relative location description","observation":"what makes this cue useful or not"}]}
Use at most 4 cues. Confidence is 0..1.
""".trimIndent()

        val content = org.json.JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", prompt))
            .put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$image64").put("detail", "low"))
        val input = org.json.JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject()
            .put("model", "gpt-5.6-sol")
            .put("reasoning", JSONObject().put("effort", "medium"))
            .put("input", input)
            .put("max_output_tokens", 650)

        val conn = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12_000; readTimeout = 25_000; doOutput = true
            setRequestProperty("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val response = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) error("OpenAI HTTP $code: ${response.take(240)}")

        val root = JSONObject(response)
        val output = root.optJSONArray("output") ?: error("No output")
        var text = ""
        loop@ for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val arr = item.optJSONArray("content") ?: continue
            for (j in 0 until arr.length()) {
                val c = arr.optJSONObject(j) ?: continue
                if (c.optString("type") == "output_text") { text = c.optString("text"); break@loop }
            }
        }
        val first = text.indexOf('{'); val last = text.lastIndexOf('}')
        if (first < 0 || last <= first) error("Reasoner did not return JSON")
        val j = JSONObject(text.substring(first, last + 1))
        val cueArray = j.optJSONArray("cues")
        val cues = mutableListOf<Cue>()
        if (cueArray != null) for (i in 0 until minOf(4, cueArray.length())) {
            val q = cueArray.optJSONObject(i) ?: continue
            cues += Cue(q.optString("label", "CUE"), q.optString("sensitivity", "LOW"), q.optString("location", ""), q.optString("observation", ""))
        }
        return Assessment(
            createdMs = SystemClock.elapsedRealtime(),
            state = j.optString("state", "INSUFFICIENT").uppercase(),
            confidence = j.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
            summary = j.optString("summary", "Scene analysed"),
            direction = j.optString("direction", "UNKNOWN"),
            speedBandMph = j.optString("speed_band_mph", "UNKNOWN"),
            cues = cues,
            rationale = j.optString("rationale", "")
        )
    }
}
