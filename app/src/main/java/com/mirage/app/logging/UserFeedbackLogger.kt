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
                    "timestamp_local", "device", "vote", "direction_feedback", "speed_feedback",
                    "mirage_detected", "mirage_direction", "mirage_stable", "mirage_stable_sec",
                    "cue_label", "cue_direction", "cue_wind_min_mps", "cue_wind_max_mps",
                    "cue_confidence", "cue_stable", "cue_stable_sec",
                    "aggregate_state", "aggregate_direction", "aggregate_confidence", "aggregate_sources",
                    "aggregate_wind_min_mps", "aggregate_wind_max_mps", "direction_agreement",
                    "signal_score", "scene_luma"
                ).joinToString(",") + "\n"
            )
        }
    }

    fun record(vote: Vote, r: AnalysisResult?): File = recordFieldFeedback(vote.name, "", "", r)

    /**
     * v0.72 calibration feedback. This is deliberately logged only; it never modifies the live
     * estimate. Direction and speed feedback can later be compared with the captured algorithm state.
     */
    fun recordFieldFeedback(vote: String, directionFeedback: String, speedFeedback: String, r: AnalysisResult?): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".csv()
        val cue = r?.motionCues?.maxByOrNull { it.confidence }
        val row = listOf(
            timestamp.csv(), device, vote.csv(), directionFeedback.csv(), speedFeedback.csv(),
            r?.sufficientSignal ?: false,
            (r?.mirageClockDirection ?: "").csv(),
            r?.mirageStable ?: false,
            r?.mirageStableForSec ?: "",
            (cue?.label ?: "").csv(),
            (cue?.clockDirection ?: "").csv(),
            cue?.estimatedWindMinMps ?: "",
            cue?.estimatedWindMaxMps ?: "",
            cue?.confidence ?: "",
            cue?.stable ?: false,
            cue?.stableForSec ?: "",
            (r?.aggregateWindState ?: "").csv(),
            (r?.aggregateWindClockDirection ?: "").csv(),
            r?.aggregateWindConfidence ?: "",
            (r?.aggregateWindSources ?: "").csv(),
            r?.aggregateWindMinMps ?: "",
            r?.aggregateWindMaxMps ?: "",
            r?.aggregateDirectionAgreement ?: "",
            r?.signalScore ?: "",
            r?.sceneLuma ?: ""
        )
        outputFile.appendText(row.joinToString(",") + "\n")
        return outputFile
    }

    fun file(): File = outputFile

    private fun String.csv(): String = "\"${replace("\"", "\"\"")}\""
}
