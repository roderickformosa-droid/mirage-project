package com.mirage.app.logging

import com.mirage.app.analysis.AnalysisResult
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Writes one CSV row per analyzed frame. Column names match Stage 1's
 * desktop CSV (logger.py CSV_FIELDS) so a field-test log can be loaded
 * straight into the desktop graphs.py without renaming anything, with
 * three EXTRA columns at the front for timing verification (see
 * TIMING_NOTES.md): the authoritative camera-frame timestamp, a wall-clock
 * cross-check timestamp, and a running frame index.
 */
class MeasurementLogger(outputFile: File) {

    companion object {
        val HEADER = listOf(
            // --- timing (Android-specific, additive vs. desktop schema) ---
            "frame_index", "camera_frame_timestamp_ns", "wallclock_elapsed_realtime_ns",
            // --- everything below matches desktop logger.py column-for-column ---
            "time_sec",
            "stab_dx", "stab_dy", "stab_rotation_deg", "stab_disturbance", "stab_tracking_ok",
            "of_angle_deg", "of_magnitude", "of_mean_magnitude", "of_active_fraction",
            "bm_angle_deg", "bm_magnitude", "bm_mean_magnitude", "bm_angular_dispersion",
            "bm_mean_response", "bm_inlier_fraction",
            "tv_activity_index", "tv_spatial_texture",
            "fd_dominant_freq_hz", "fd_spectral_energy", "fd_spectral_centroid_hz",
            "sufficient_signal", "mirage_clock", "mirage_stable", "mirage_stable_for_sec", "signal_score",
            "motion_cue_count", "best_cue_label", "best_cue_clock", "best_cue_wind_min_mps",
            "best_cue_wind_max_mps", "best_cue_confidence", "best_cue_stable",
            "best_cue_distance_m", "best_cue_distance_source", "best_cue_physical_motion_mps",
            "best_cue_wind_quality"
        )
    }

    private val writer: BufferedWriter = BufferedWriter(FileWriter(outputFile))
    private var sessionStartNs: Long? = null

    init {
        writer.write(HEADER.joinToString(","))
        writer.newLine()
    }

    fun logResult(r: AnalysisResult) {
        val startNs = sessionStartNs ?: r.cameraFrameTimestampNs.also { sessionStartNs = it }
        val timeSec = (r.cameraFrameTimestampNs - startNs) / 1_000_000_000.0

        val bestCue = r.motionCues.maxByOrNull { it.confidence }
        val row = listOf(
            r.frameIndex, r.cameraFrameTimestampNs, r.wallClockElapsedRealtimeNs,
            "%.4f".format(timeSec),
            r.stabDx, r.stabDy, r.stabRotationDeg, r.stabDisturbance, r.stabTrackingOk,
            r.ofAngleDeg, r.ofMagnitude, r.ofMeanMagnitude, r.ofActiveFraction,
            r.bmAngleDeg, r.bmMagnitude, r.bmMeanMagnitude, r.bmAngularDispersion,
            r.bmMeanResponse, r.bmInlierFraction,
            r.tvActivityIndex, r.tvSpatialTexture,
            r.fdDominantFreqHz, r.fdSpectralEnergy, r.fdSpectralCentroidHz,
            r.sufficientSignal, r.mirageClockDirection, r.mirageStable, r.mirageStableForSec, r.signalScore,
            r.motionCues.size, bestCue?.label ?: "", bestCue?.clockDirection ?: "",
            bestCue?.estimatedWindMinMps ?: "", bestCue?.estimatedWindMaxMps ?: "",
            bestCue?.confidence ?: "", bestCue?.stable ?: ""
        )
        writer.write(row.joinToString(","))
        writer.newLine()
    }

    fun close() {
        writer.flush()
        writer.close()
    }
}
