package com.mirage.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.mirage.app.analysis.FrameAnalyzer
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Owns the CameraX pipeline: which physical camera is selected, the three
 * concurrent use cases (Preview, ImageAnalysis, VideoCapture), and the
 * Camera2-interop plumbing needed for manual focus/exposure lock.
 *
 * HONEST LIMITATION: focus/exposure manual-lock support is genuinely
 * hardware- and driver-dependent. This requests CONTROL_AF_MODE_OFF at a
 * captured focus distance, and CONTROL_AE_MODE_OFF with a captured
 * exposure time/ISO/white balance. Some phones (especially budget ones,
 * or camera2 LEGACY-level devices) silently ignore some of these and keep
 * adjusting anyway. The app reports which locks were REQUESTED, not
 * which ones the hardware actually honored -- watch the preview after
 * locking to visually confirm it stopped hunting for focus/brightness
 * before trusting a session.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    data class CameraOption(val cameraId: String, val label: String, val cameraSelector: CameraSelector)

    val analysisExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    // Latest capture-result values, kept live so "lock" can freeze at
    // whatever the camera currently reads, rather than an arbitrary guess.
    @Volatile private var lastFocusDistance: Float? = null
    @Volatile private var lastExposureTimeNs: Long? = null
    @Volatile private var lastSensitivity: Int? = null

    fun listRearCameras(): List<CameraOption> {
        val provider = cameraProvider ?: return emptyList()
        val options = ArrayList<CameraOption>()
        for (info in provider.availableCameraInfos) {
            val camera2Info = Camera2CameraInfo.from(info)
            val facing = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val id = camera2Info.cameraId
                val focalLengths = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )
                val label = "Rear camera $id" + (focalLengths?.firstOrNull()?.let { " (%.1fmm)".format(it) } ?: "")
                options.add(CameraOption(id, label, CameraSelector.Builder()
                    .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
                    .build()))
            }
        }
        return options
    }

    fun startCamera(
        previewView: androidx.camera.view.PreviewView,
        cameraOption: CameraOption?,
        analysisTargetSize: Size,
        onAnalysisReady: (ImageAnalysis, Int, Int) -> Unit
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val selector = cameraOption?.cameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA

            val previewBuilder = Preview.Builder()
            // Register a Camera2 capture callback so we always know the
            // CURRENT focus distance / exposure the camera has settled on,
            // which is what "lock" freezes onto.
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: android.hardware.camera2.CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDistance = it }
                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureTimeNs = it }
                        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastSensitivity = it }
                    }
                }
            )
            val previewUseCase = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(analysisTargetSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            val videoCap = VideoCapture.withOutput(recorder)

            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, previewUseCase, analysis, videoCap)

            preview = previewUseCase
            imageAnalysis = analysis
            videoCapture = videoCap

            onAnalysisReady(analysis, analysisTargetSize.width, analysisTargetSize.height)
        }, mainExecutor)
    }

    /** Requests AF off + fixed focus distance at whatever the camera currently reads. */
    fun setFocusLocked(locked: Boolean) {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)
        if (locked) {
            val distance = lastFocusDistance ?: 0f
            control.setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                    .build()
            )
        } else {
            control.setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    .build()
            )
        }
    }

    /** Requests AE off + fixed exposure time/ISO at whatever the camera currently reads. */
    fun setExposureLocked(locked: Boolean) {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)
        if (locked) {
            val expTime = lastExposureTimeNs ?: 8_000_000L
            val iso = lastSensitivity ?: 400
            control.setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expTime)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    .build()
            )
        } else {
            control.setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    .build()
            )
        }
    }


    fun zoomRange(): Pair<Float, Float> {
        val z = camera?.cameraInfo?.zoomState?.value
        return if (z != null) z.minZoomRatio to z.maxZoomRatio else 1f to 1f
    }

    fun currentZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val z = cam.cameraInfo.zoomState.value
        val safe = if (z != null) ratio.coerceIn(z.minZoomRatio, z.maxZoomRatio) else ratio.coerceAtLeast(1f)
        cam.cameraControl.setZoomRatio(safe)
    }

    fun startRecording(outputFile: File, onFinalized: (Boolean, String?) -> Unit) {
        val videoCap = videoCapture ?: return
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        activeRecording = videoCap.output
            .prepareRecording(context, outputOptions)
            .apply {
                // Audio isn't essential for measurement purposes, but a spoken
                // "holding... moving harder..." commentary track is exactly
                // the kind of ground-truth annotation this field test wants,
                // per the session-narration plan -- so we include it.
                withAudioEnabled()
            }
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e("CameraController", "Recording error: ${event.error}")
                            onFinalized(false, event.cause?.message)
                        } else {
                            onFinalized(true, null)
                        }
                    }
                    else -> { /* Start/Status/Pause events -- nothing needed here */ }
                }
            }
    }

    fun stopCamera() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        camera = null
        videoCapture = null
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun isRecording(): Boolean = activeRecording != null
}
