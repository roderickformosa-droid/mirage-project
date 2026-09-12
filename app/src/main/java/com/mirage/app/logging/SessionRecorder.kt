package com.mirage.app.logging

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.mirage.app.analysis.AnalysisResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bundles one field-test recording into a single folder:
 *   sessions/<timestamp>/video.mp4        -- from CameraController
 *   sessions/<timestamp>/measurements.csv -- from MeasurementLogger
 *   sessions/<timestamp>/session_info.txt -- timing + config metadata
 *
 * The session_info.txt is what makes sync verifiable later: it records
 * BOTH clocks (camera-frame timestamp domain and wall-clock
 * elapsedRealtimeNanos) at recording start, so if you ever need to check
 * "does frame timestamp X in the CSV really line up with time X in the
 * video," you have the actual numbers to check it against rather than an
 * assumption.
 */
class SessionRecorder(context: Context) {

    val sessionDir: File
    val videoFile: File
    private val logger: MeasurementLogger
    private var recordingStartWallClockNs: Long = 0
    private var recordingStartCameraTsNs: Long? = null
    private var rowCount = 0

    init {
        val root = File(context.getExternalFilesDir(null), "sessions")
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionDir = File(root, name).apply { mkdirs() }
        videoFile = File(sessionDir, "video.mp4")
        logger = MeasurementLogger(File(sessionDir, "measurements.csv"))
        recordingStartWallClockNs = SystemClock.elapsedRealtimeNanos()
    }

    fun logResult(r: AnalysisResult) {
        if (recordingStartCameraTsNs == null) recordingStartCameraTsNs = r.cameraFrameTimestampNs
        logger.logResult(r)
        rowCount++
    }

    /** Call once when recording actually stops, to finalize metadata. */
    fun finish(videoRecordingSucceeded: Boolean, errorMessage: String?) {
        logger.close()
        val info = buildString {
            appendLine("Mirage Field Tester -- session metadata")
            appendLine("device_model=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android_release=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("video_recording_succeeded=$videoRecordingSucceeded")
            if (errorMessage != null) appendLine("video_error=$errorMessage")
            appendLine("measurement_rows_logged=$rowCount")
            appendLine()
            appendLine("--- timing sync info ---")
            appendLine("recording_start_wallclock_elapsedRealtimeNanos=$recordingStartWallClockNs")
            appendLine("first_logged_camera_frame_timestamp_ns=${recordingStartCameraTsNs ?: -1}")
            appendLine(
                "NOTE: camera_frame_timestamp_ns in measurements.csv is the authoritative " +
                    "per-frame timestamp from CameraX's ImageProxy (same clock domain the video " +
                    "encoder uses for presentation timestamps on this device). " +
                    "wallclock_elapsed_realtime_ns in the CSV is a SEPARATE cross-check clock " +
                    "captured at analysis time, included specifically so sync can be verified " +
                    "experimentally: if you scrub the video to a moment you can visually identify " +
                    "(e.g. the instant you tap RECORD, or a deliberate tap/flash), find the nearest " +
                    "camera_frame_timestamp_ns row and confirm the two clocks agree on elapsed time " +
                    "since session start to within a frame interval. If they drift apart over a long " +
                    "session, that's a real finding to report back, not something to ignore."
            )
        }
        File(sessionDir, "session_info.txt").writeText(info)
    }
}
