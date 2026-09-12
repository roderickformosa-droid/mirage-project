package com.mirage.app.logging

import android.content.Context
import android.os.Build
import com.mirage.app.analysis.AnalysisResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Field-test validation logger. Operator feedback NEVER changes the live algorithm decision.
 * It is stored beside the algorithm snapshot so agreement can be analysed later.
 */
class UserFeedbackLogger(context: Context) {

    enum class Vote { AGREE, NOT_SURE, DISAGREE }

    private val outputFile: File

    init {
        val root = File(context.getExternalFilesDir(null), "field_feedback").apply { mkdirs() }
        outputFile = File(root, "operator_feedback.csv")
        if (!outputFile.exists() || outputFile.length() == 0L) {
            outputFile.appendText(
                listOf(
                    "timestamp_local", "device", "vote",
                    "mirage_detected", "mirage_direction", "mirage_stable", "mirage_stable_sec",
                    "cue_label", "cue_direction", "cue_wind_min_mps", "cue_wind_max_mps",
                    "cue_confidence", "cue_stable", "cue_stable_sec",
                    "target_label", "target_confidence", "signal_score", "scene_luma"
                ).joinToString(",") + "\n"
            )
        }
    }

    fun record(vote: Vote, r: AnalysisResult?): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".csv()
        val cue = r?.motionCues?.maxByOrNull { it.confidence }
        val row = listOf(
            timestamp.csv(), device, vote.name,
            r?.sufficientSignal ?: false,
            (r?.mirageClockDirection ?: "").csv(),
            r?.mirageStable ?: false,
            r?.mirageStableForSec ?: "",
            (cue?.label ?: "").csv(),
            (cue?.clockDirection ?: "").csv(),
            cue?.estimatedWindMinMps ?: "",
            cue?.estimatedWindMaxMps ?: "",
            cue?.confidence ?: "",
            cue?.stable ?: "",
            cue?.stableForSec ?: "",
            (r?.targetLabel ?: "").csv(),
            r?.targetConfidence ?: "",
            r?.signalScore ?: "",
            r?.sceneLuma ?: ""
        )
        outputFile.appendText(row.joinToString(",") + "\n")
        return outputFile
    }

    fun file(): File = outputFile

    private fun String.csv(): String = "\"${replace("\"", "\"\"")}\""
}
