package com.mirage.app.logging

import android.content.Context
import com.mirage.app.analysis.AnalysisResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceTrainingMemory(private val context: Context) {
    private val prefs = context.getSharedPreferences("voice_training_memory", Context.MODE_PRIVATE)
    private val dir = File(context.getExternalFilesDir(null), "voice_training")
    private val csv = File(dir, "voice_annotations.csv")

    data class Stats(val samples: Int, val target: Int = 100) {
        val percent: Int get() = ((samples.coerceAtMost(target) * 100.0) / target).toInt()
    }

    init {
        dir.mkdirs()
        if (!csv.exists()) {
            csv.writeText("timestamp,command,cue,state,direction,speed_min_mph,speed_max_mph,tracked_cues,learning_percent,optical_mode\n")
        }
    }

    fun stats(): Stats = Stats(prefs.getInt("samples", 0))

    fun store(command: String, result: AnalysisResult?): Stats {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        val cue = result?.aggregateWindSources?.joinToString("+") ?: ""
        val dir = result?.aggregateWindClockDirection ?: ""
        val minMph = result?.aggregateWindMinMps?.times(2.2369362920544) ?: 0.0
        val maxMph = result?.aggregateWindMaxMps?.times(2.2369362920544) ?: 0.0
        val line = listOf(
            ts, command, cue, result?.aggregateWindState ?: "", dir,
            "%.1f".format(Locale.US, minMph), "%.1f".format(Locale.US, maxMph),
            result?.trackedCueCount ?: 0, result?.learningPercent ?: 0, result?.opticalMode ?: ""
        ).joinToString(",") { it.toString().replace(",", " ") }
        csv.appendText(line + "\n")
        val n = prefs.getInt("samples", 0) + 1
        prefs.edit().putInt("samples", n).apply()
        return Stats(n)
    }
}
