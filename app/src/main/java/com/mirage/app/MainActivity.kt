package com.mirage.app

import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.speech.RecognizerIntent
import android.os.BatteryManager
import android.graphics.RectF
import android.content.res.ColorStateList
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.core.content.ContextCompat
import com.mirage.app.analysis.AnalysisResult
import com.mirage.app.analysis.CoordinateMapper
import com.mirage.app.analysis.FrameAnalyzer
import com.mirage.app.analysis.OpticalModeDetector
import com.mirage.app.analysis.RangeEstimator
import com.mirage.app.camera.CameraController
import com.mirage.app.databinding.ActivityMainBinding
import com.mirage.app.logging.SessionRecorder
import com.mirage.app.logging.UserFeedbackLogger
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraController
    private var frameAnalyzer: FrameAnalyzer? = null
    private var sessionRecorder: SessionRecorder? = null
    private lateinit var feedbackLogger: UserFeedbackLogger
    private var latestAnalysisResult: AnalysisResult? = null
    private var publishedWindResult: AnalysisResult? = null
    private var lastWindPublishMs = 0L
    private var lastFeedbackVotePublishMs = -1L
    private var lastAnalysisReceivedMs = 0L
    private var analysisWatchStartedMs = 0L
    private var lastAutomaticCameraRestartMs = 0L
    private var opticalOverride = OpticalModeDetector.Override.AUTO

    // RANGE ASSIST: orientation is local; location is optional. Cloud Vision uses internet only
    // for rare fallback object-label checks when local recognition is uncertain.
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var lastAmbientLux: Float? = null
    private var autoBrightnessEnabled = true
    private var manualBrightnessPercent = 70
    private var cameraHeadingDeg: Double? = null
    private var cameraPitchDeg: Double? = null
    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null
    private var locationEnabled = false
    private var batteryTempC: Double? = null
    private var batteryHeatShutdown = false
    private var scaleDetector: ScaleGestureDetector? = null

    private val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val r = FloatArray(9); val o = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(r, event.values)
            SensorManager.getOrientation(r, o)
            cameraHeadingDeg = (Math.toDegrees(o[0].toDouble()) + 360.0) % 360.0
            // Rear camera optical axis is approximately horizontal when this value is near zero.
            // Negative values are treated as downward/depression angles for range geometry.
            cameraPitchDeg = -Math.toDegrees(o[1].toDouble())
            updateNavigationReadout()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val lux = event.values.firstOrNull() ?: return
            lastAmbientLux = lux
            frameAnalyzer?.setAmbientLux(lux)
            if (autoBrightnessEnabled) applyAutoBrightness(lux)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            updateNavigationReadout()
        }
    }

    private var thermalShutdown = false
    private var currentThermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var waitingForManualResume = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val normalAnalysisHz = 7.0
    private val reducedAnalysisHz = 3.0
    private val maxContinuousSessionMs = 10L * 60L * 1000L
    private val minFreeBytesToStartRecording = 500L * 1024L * 1024L
    private val stopRecordingBelowFreeBytes = 250L * 1024L * 1024L
    private val storageCheckIntervalMs = 5_000L
    private val analysisTargetSize = Size(640, 480)

    // AUTO ZOOM: samples several sensible zoom ratios and keeps the one producing the best
    // mirage/visual-cue score. Manual +/- instantly disables auto so the shooter stays in control.
    private var autoZoomEnabled = true
    private var autoZoomSearching = false
    private var zoomCandidates: List<Float> = emptyList()
    private var zoomCandidateIndex = 0
    private var zoomScoreSum = 0.0
    private var zoomScoreCount = 0
    private val zoomResults = linkedMapOf<Float, Double>()
    private var zoomCandidateStartedMs = 0L
    private var nextAutoZoomSweepMs = 0L
    private var currentSignalScore = 0.0
    private var lastBatteryTempUpdateMs = 0L

    private val sessionTimeoutRunnable = Runnable {
        if (!waitingForManualResume) stopForSafety(
            "10-MINUTE SAFETY PAUSE — TAP RESUME", true,
            "10-minute field-test limit reached. Processing stopped until you manually resume."
        )
    }

    private val storageCheckRunnable = object : Runnable {
        override fun run() {
            if (!::cameraController.isInitialized || !cameraController.isRecording()) return
            val freeBytes = availableStorageBytes()
            if (freeBytes in 0 until stopRecordingBelowFreeBytes) {
                cameraController.stopRecording(); binding.recordButton.text = "RECORD"
                Toast.makeText(this@MainActivity,
                    "Recording stopped to protect storage space (less than 250 MB free).",
                    Toast.LENGTH_LONG).show(); return
            }
            mainHandler.postDelayed(this, storageCheckIntervalMs)
        }
    }

    private val analysisWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!waitingForManualResume && frameAnalyzer != null) {
                val now = SystemClock.elapsedRealtime()
                val age = if (lastAnalysisReceivedMs > 0L) now - lastAnalysisReceivedMs else now - analysisWatchStartedMs
                if (age > 2800L) {
                    binding.analysisStageText.text = "ANALYSIS INTERRUPTED • REACQUIRING"
                    binding.learningDetailText.text = "CAMERA PIPELINE IS BEING RE-ACQUIRED"
                }
                if (age > 5200L && now - lastAutomaticCameraRestartMs > 12000L) {
                    lastAutomaticCameraRestartMs = now
                    try { cameraController.stopCamera() } catch (_: Throwable) {}
                    frameAnalyzer = null
                    mainHandler.postDelayed({ if (!waitingForManualResume) initCameraOnceOpenCvReady() }, 350L)
                }
            }
            mainHandler.postDelayed(this, 2000L)
        }
    }

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) { locationEnabled = true; startLocationUpdates(); binding.locationButton.text = "GPS ON" }
        else { locationEnabled = false; binding.locationButton.text = "GPS OFF"; Toast.makeText(this, "Location is optional; range assist will stay limited.", Toast.LENGTH_LONG).show() }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[android.Manifest.permission.CAMERA] == true) initCameraOnceOpenCvReady()
        else Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
        if (grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) startLocationUpdates()
    }

    private val voiceZoomLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.lowercase() ?: return@registerForActivityResult
        when {
            "increase magnification" in heard || "zoom in" in heard || "increase zoom" in heard -> manualZoom(+1)
            "decrease magnification" in heard || "zoom out" in heard || "decrease zoom" in heard -> manualZoom(-1)
            else -> Toast.makeText(this, "Say: increase magnification or decrease magnification", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Field use: keep the display awake while this activity is visible so a mirage
        // observation is not lost to Android screen dimming/timeout.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!OpenCVLoaderHelper.init()) {
            Toast.makeText(this, "OpenCV failed to load -- cannot continue.", Toast.LENGTH_LONG).show()
        }
        cameraController = CameraController(this, this)
        feedbackLogger = UserFeedbackLogger(this)
        setupFeedbackControls()
        setupCloudUsageControls()
        setupOpticalModeControls()

        setupRangeAssist()
        setupDisplayControls()
        binding.locationButton.text = "GPS OFF"
        binding.locationButton.setOnClickListener {
            if (locationEnabled) {
                stopLocationUpdates()
                locationEnabled = false
                binding.locationButton.text = "GPS OFF"
            } else {
                val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine || coarse) { locationEnabled = true; startLocationUpdates(); binding.locationButton.text = "GPS ON" }
                else requestLocationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }

        val needed = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) initCameraOnceOpenCvReady() else requestPermissions.launch(needed.toTypedArray())

        binding.debugToggle.setOnCheckedChangeListener { _, checked ->
            binding.debugOverlay.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.focusLockToggle.setOnCheckedChangeListener { _, checked -> cameraController.setFocusLocked(checked) }
        binding.exposureLockToggle.setOnCheckedChangeListener { _, checked -> cameraController.setExposureLocked(checked) }
        binding.recordButton.setOnClickListener { toggleRecording() }
        binding.resumeButton.setOnClickListener { resumeAfterSafetyStop() }

        binding.autoRoiToggle.isChecked = true
        binding.roiOverlay.visibility = View.GONE
        binding.autoRoiToggle.setOnCheckedChangeListener { _, checked ->
            frameAnalyzer?.setAutoRoiEnabled(checked)
            binding.roiOverlay.visibility = if (checked) View.GONE else View.VISIBLE
        }

        binding.autoZoomToggle.isChecked = true
        binding.autoZoomToggle.setOnCheckedChangeListener { _, checked ->
            autoZoomEnabled = checked
            if (checked) beginAutoZoomSweep() else autoZoomSearching = false
            updateZoomText()
        }
        binding.zoomPlus.setOnClickListener { manualZoom(+1) }
        binding.zoomMinus.setOnClickListener { manualZoom(-1) }
        binding.voiceZoomButton.setOnClickListener { startVoiceZoom() }
        setupPinchZoom()

        installThermalProtection()
    }

    private fun installThermalProtection() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                runOnUiThread { handleThermalStatus(status) }
            }
            thermalListener = listener
            powerManager.addThermalStatusListener(mainExecutor, listener)
            handleThermalStatus(powerManager.currentThermalStatus)
        }
    }

    private fun handleThermalStatus(status: Int) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        currentThermalStatus = status
        when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> {
                refreshBrightnessForThermalState()
                if (!thermalShutdown) {
                    thermalShutdown = true
                    stopForSafety("THERMAL SAFETY STOP — COOL PHONE", false,
                        "Phone is too hot. Camera and processing have stopped. Resume is locked until it cools.")
                } else binding.resumeButton.isEnabled = false
            }
            status >= PowerManager.THERMAL_STATUS_MODERATE -> {
                frameAnalyzer?.setTargetHz(reducedAnalysisHz)
                // Bright displays add heat. Cap app brightness while the device is already warm.
                refreshBrightnessForThermalState()
                if (thermalShutdown && waitingForManualResume) {
                    binding.resumeButton.isEnabled = true
                    binding.signalQualityText.text = "COOLED — TAP RESUME"
                }
            }
            else -> {
                frameAnalyzer?.setTargetHz(normalAnalysisHz)
                refreshBrightnessForThermalState()
                if (thermalShutdown && waitingForManualResume) {
                    binding.resumeButton.isEnabled = true
                    binding.signalQualityText.text = "COOLED — TAP RESUME"
                }
            }
        }
    }

    private fun updateBatteryTemperature() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (raw != Int.MIN_VALUE && ::binding.isInitialized) {
            val c = raw / 10.0
            batteryTempC = c
            binding.temperatureText.text = "BATTERY TEMP %.1f°C".format(c)
            applyBatteryHeatPolicy(c)
        }
    }

    private fun applyBatteryHeatPolicy(c: Double) {
        if (waitingForManualResume) {
            if (batteryHeatShutdown && c <= 36.5) {
                binding.resumeButton.isEnabled = true
                binding.signalQualityText.text = "BATTERY COOLED — TAP RESUME"
            }
            return
        }
        when {
            c >= 38.0 -> {
                batteryHeatShutdown = true
                stopForSafety("BATTERY HOT — COOL PHONE", false,
                    "Battery reached %.1f°C. Camera and analysis stopped to reduce heat.".format(c))
            }
            c >= 37.0 -> frameAnalyzer?.setTargetHz(2.0)
            c >= 36.0 -> frameAnalyzer?.setTargetHz(4.0)
            else -> {
                val warm = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                    currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
                frameAnalyzer?.setTargetHz(if (warm) reducedAnalysisHz else normalAnalysisHz)
            }
        }
    }

    private fun thermalLabel(status: Int): String = when {
        status >= PowerManager.THERMAL_STATUS_SEVERE -> "TEMP HOT"
        status >= PowerManager.THERMAL_STATUS_MODERATE -> "TEMP WARM"
        status >= PowerManager.THERMAL_STATUS_LIGHT -> "TEMP +"
        else -> "TEMP OK"
    }

    private fun stopForSafety(message: String, resumeAllowed: Boolean, toastMessage: String) {
        waitingForManualResume = true; cancelSessionTimeout(); stopStorageChecks()
        if (::cameraController.isInitialized) cameraController.stopCamera()
        frameAnalyzer = null; autoZoomSearching = false
        binding.recordButton.isEnabled = false; binding.recordButton.text = "RECORD"
        binding.resumeButton.visibility = View.VISIBLE; binding.resumeButton.isEnabled = resumeAllowed
        binding.signalQualityText.text = message; binding.signalQualityText.setBackgroundColor(0x88AA0000.toInt())
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()
    }

    private fun resumeAfterSafetyStop() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            thermalShutdown && currentThermalStatus > PowerManager.THERMAL_STATUS_MODERATE) {
            Toast.makeText(this, "Phone is still too hot. Let it cool before resuming.", Toast.LENGTH_LONG).show(); return
        }
        if (batteryHeatShutdown && (batteryTempC ?: 99.0) > 36.5) {
            Toast.makeText(this, "Battery is still above 36.5°C. Let it cool before resuming.", Toast.LENGTH_LONG).show(); return
        }
        waitingForManualResume = false; thermalShutdown = false; batteryHeatShutdown = false
        binding.resumeButton.visibility = View.GONE; binding.resumeButton.isEnabled = false
        binding.recordButton.isEnabled = true
        binding.signalQualityText.text = "RESTARTING CAMERA..."
        initCameraOnceOpenCvReady()
    }

    private fun scheduleSessionTimeout() { cancelSessionTimeout(); mainHandler.postDelayed(sessionTimeoutRunnable, maxContinuousSessionMs) }
    private fun cancelSessionTimeout() { mainHandler.removeCallbacks(sessionTimeoutRunnable) }
    private fun startStorageChecks() { stopStorageChecks(); mainHandler.postDelayed(storageCheckRunnable, storageCheckIntervalMs) }
    private fun stopStorageChecks() { mainHandler.removeCallbacks(storageCheckRunnable) }
    private fun availableStorageBytes(): Long = getExternalFilesDir(null)?.usableSpace ?: filesDir.usableSpace

    override fun onDestroy() {
        cancelSessionTimeout(); stopStorageChecks(); mainHandler.removeCallbacks(analysisWatchdogRunnable)
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(orientationListener)
            sensorManager.unregisterListener(lightListener)
        }
        try { locationManager?.removeUpdates(locationListener) } catch (_: SecurityException) {}
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        }
        super.onDestroy()
    }

    private fun initCameraOnceOpenCvReady() {
        if (waitingForManualResume) return
        cameraController.startCamera(binding.previewView, null, analysisTargetSize) { analysis, _, _ ->
            attachAnalyzer(analysis); scheduleSessionTimeout()
            mainHandler.postDelayed({ if (autoZoomEnabled) beginAutoZoomSweep() }, 900L)
        }
    }


    private fun attachAnalyzer(analysis: ImageAnalysis) {
        val initialHz = when {
            (batteryTempC ?: 0.0) >= 37.0 -> 2.0
            (batteryTempC ?: 0.0) >= 36.0 -> 4.0
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> reducedAnalysisHz
            else -> normalAnalysisHz
        }
        val analyzer = FrameAnalyzer(
            context = applicationContext,
            targetHz = initialHz,
            roiSupplier = { analysisW, analysisH ->
                val mapper = CoordinateMapper(binding.previewView.width, binding.previewView.height, analysisW, analysisH)
                mapper.viewRectToAnalysisRect(binding.roiOverlay.roiInViewCoords())
            },
            onResult = { result, _ -> runOnUiThread { onAnalysisResult(result) } }
        )
        analyzer.setAutoRoiEnabled(binding.autoRoiToggle.isChecked)
        analyzer.setAmbientLux(lastAmbientLux)
        analyzer.setOpticalModeOverride(opticalOverride)
        frameAnalyzer = analyzer
        analysis.setAnalyzer(cameraController.analysisExecutor, analyzer)
        lastAnalysisReceivedMs = 0L
        analysisWatchStartedMs = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(analysisWatchdogRunnable)
        mainHandler.postDelayed(analysisWatchdogRunnable, 2000L)
        binding.roiOverlay.onRoiChanged = { frameAnalyzer?.resetForNewRoi() }
    }

    private fun onAnalysisResult(raw: AnalysisResult) {
        lastAnalysisReceivedMs = SystemClock.elapsedRealtime()
        currentSignalScore = raw.signalScore
        val tempNow = SystemClock.elapsedRealtime()
        if (tempNow - lastBatteryTempUpdateMs >= 1500L) {
            lastBatteryTempUpdateMs = tempNow
            updateBatteryTemperature()
        }

        val rangeCtx = currentRangeContext()
        val enrichedCues = raw.motionCues.map { RangeEstimator.enrich(it, raw.fullWidthPx, rangeCtx) }
        val r = raw.copy(motionCues = enrichedCues)
        latestAnalysisResult = r

        // v0.68 intentionally hides frame-by-frame arrows/labels from the normal field UI.
        // The overlay remains available to debug builds but does not drive the user's conclusion.
        binding.windCueOverlay.visibility = View.GONE

        binding.opticalModeText.text = when (r.opticalMode) {
            "SPOTTING SCOPE" -> "SPOTTING SCOPE • ANALYSIS ACTIVE"
            "PHONE CAMERA" -> "PHONE CAMERA • ANALYSIS ACTIVE"
            else -> "DETECTING OPTICAL MODE…"
        }
        binding.analysisStageText.text = r.analysisStage
        binding.learningProgress.progress = r.learningPercent
        binding.learningText.text = if (r.learningUseful) "LEARNING ${r.learningPercent}%" else "SCANNING ${r.learningPercent}%"
        binding.learningDetailText.text = buildString {
            append("${r.trackedCueCount} CUE")
            if (r.trackedCueCount != 1) append("S")
            append(" TRACKED")
            append(" • MIRAGE ")
            append(if (r.sufficientSignal) "DETECTED" else "SEARCHING")
        }
        binding.scopeGuidanceText.visibility = if (r.opticalMode == "SPOTTING SCOPE") View.VISIBLE else View.GONE
        if (r.opticalMode == "SPOTTING SCOPE") {
            binding.scopeGuidanceText.text = when {
                r.learningPercent < 18 -> "STEP 1 • FOCUS ON TARGET / SCENE"
                r.sufficientSignal -> "MIRAGE DETECTED • KEEP THE AIR MASS STEADY IN VIEW"
                else -> "STEP 2 • MIRAGE: FOCUS APPROX. MIDWAY TO TARGET"
            }
        }
        val learningColor = when {
            !r.learningUseful || r.learningPercent < 30 -> 0xFFD32F2F.toInt()
            r.learningPercent < 70 -> 0xFFFFA000.toInt()
            else -> 0xFF2E7D32.toInt()
        }
        binding.learningProgress.progressTintList = ColorStateList.valueOf(learningColor)

        val range = RangeEstimator.distance(rangeCtx)
        updateNavigationReadout(range)

        // Publish at most one new wind condition every 3 seconds. Internal analysis remains fast.
        val publishable = r.aggregateWindState != "INSUFFICIENT" && r.aggregateWindConfidence >= 0.42 && r.learningPercent >= 35 && r.aggregateWindMaxMps > 0.0
        if (publishable && tempNow - lastWindPublishMs >= 3000L) {
            publishedWindResult = r
            lastWindPublishMs = tempNow
        }

        val published = publishedWindResult
        val freshPublished = published != null && tempNow - lastWindPublishMs <= 6000L
        if (publishable && published != null) {
            showPublishedWind(published, current = true)
            binding.feedbackPanel.visibility = if (lastFeedbackVotePublishMs == lastWindPublishMs) View.GONE else View.VISIBLE
        } else if (freshPublished && published != null) {
            showPublishedWind(published, current = false)
            binding.feedbackPanel.visibility = View.GONE
        } else {
            binding.signalQualityText.clearAnimation()
            binding.windArrowText.clearAnimation()
            binding.resultCard.visibility = View.GONE
            binding.windVisualPanel.visibility = View.GONE
            binding.feedbackPanel.visibility = View.GONE
        }

        // Hidden engineering traces remain logged for diagnosis without cluttering the live screen.
        binding.readoutText.text = buildString {
            appendLine("MODE: ${r.opticalMode} • ${r.analysisStage}")
            appendLine("LEARNING: ${r.learningPercent}% • ${r.trackedCueCount} tracked cues")
            appendLine("FUSED: ${r.aggregateWindState} • ${r.aggregateWindClockDirection}")
            appendLine("confidence %.0f%% • agreement %.0f%%".format(r.aggregateWindConfidence * 100.0, r.aggregateDirectionAgreement * 100.0))
            appendLine("sources: ${r.aggregateWindSources}")
            appendLine("MIRAGE: ${if (r.sufficientSignal) r.mirageClockDirection else "not detected"}")
        }

        binding.graphActivity.addValue(r.tvActivityIndex.toFloat())
        binding.graphAngle.addValue(r.bmAngleDeg.toFloat())
        binding.graphMagnitude.addValue(r.bmMagnitude.toFloat())
        binding.graphStabResidual.addValue(kotlin.math.hypot(r.stabDx, r.stabDy).toFloat())
        if (binding.debugOverlay.visibility == View.VISIBLE) binding.debugOverlay.update(r, analysisRoiToView(r))
        if (r.opticalMode == "SPOTTING SCOPE") {
            // Avoid digital zoom sweeps destabilizing an already magnified scope image.
            autoZoomSearching = false
        } else {
            handleAutoZoom(r)
        }
        sessionRecorder?.logResult(r)
        updateCloudUsageUi()
    }

    private fun showPublishedWind(r: AnalysisResult, current: Boolean) {
        val arrow = directionArrow(r.aggregateWindClockDirection)
        val stable = r.aggregateWindState == "STABLE"
        val changing = r.aggregateWindState == "CHANGING"
        val side = windSideLabel(r.aggregateWindClockDirection)
        val going = windGoingLabel(r.aggregateWindClockDirection)
        val (lowMph, highMph) = mphBracket(r.aggregateWindMinMps, r.aggregateWindMaxMps)

        binding.resultCard.visibility = View.VISIBLE
        binding.windVisualPanel.visibility = View.VISIBLE
        binding.windArrowText.visibility = View.VISIBLE
        binding.windArrowText.text = arrow
        binding.windDetailText.text = "$lowMph–$highMph mph EST."
        binding.windSideText.text = "$side • $going"
        binding.cueEvidenceText.text = cueEvidenceLabel(r.aggregateWindSources, r.sufficientSignal)
        binding.resultMessageText.text = when {
            stable && current -> "Consistent movement detected across the current observation window."
            changing && current -> "Wind cues are changing; continuing to analyse the scene."
            else -> "Recent estimate; continuing to scan for updated visual evidence."
        }
        binding.signalQualityText.text = when {
            stable -> "● STABLE CONDITION"
            changing -> "● CHANGING CONDITION"
            else -> "WIND CONDITION"
        }
        binding.signalQualityText.setTextColor(when {
            stable && current -> 0xFF35E66F.toInt()
            changing && current -> 0xFFFF6B6B.toInt()
            else -> 0xFFE0E0E0.toInt()
        })
        binding.windArrowText.setTextColor(when {
            stable && current -> 0xFF35E66F.toInt()
            changing && current -> 0xFFFFB74D.toInt()
            else -> 0xFFE0E0E0.toInt()
        })
        binding.windVisualLabel.text = "$side\n$going"
        binding.windDirectionVisual.setWind(r.aggregateWindClockDirection, stable && current)

        binding.signalQualityText.clearAnimation()
        binding.windArrowText.clearAnimation()
        if (current && (stable || changing)) {
            val pulse = AlphaAnimation(1.0f, if (stable) 0.62f else 0.72f).apply {
                duration = if (stable) 700L else 500L
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            binding.signalQualityText.startAnimation(pulse)
            if (stable) binding.windArrowText.startAnimation(AlphaAnimation(1.0f, 0.62f).apply {
                duration = 700L; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE
            })
        }
    }

    /** Two-mile-per-hour field bracket, always marked EST. to avoid false precision. */
    private fun mphBracket(minMps: Double, maxMps: Double): Pair<Int, Int> {
        val midpointMph = ((minMps + maxMps) * 0.5 * 2.236936).coerceAtLeast(0.0)
        val center = kotlin.math.round(midpointMph).toInt()
        val low = maxOf(0, center - 1)
        return low to (low + 2)
    }

    /** "Right wind" means the observed flow is travelling from right toward left. */
    private fun windSideLabel(clock: String): String {
        val hour = clock.substringBefore(" ").toIntOrNull() ?: return "Variable wind"
        return when (hour) {
            7, 8, 9, 10, 11 -> "Right Wind"
            1, 2, 3, 4, 5 -> "Left Wind"
            else -> "Head/Tail Wind"
        }
    }

    private fun windGoingLabel(clock: String): String {
        val hour = clock.substringBefore(" ").toIntOrNull() ?: return "direction changing"
        return when (hour) {
            7, 8, 9, 10, 11 -> "going left"
            1, 2, 3, 4, 5 -> "going right"
            12 -> "moving up-frame"
            6 -> "moving down-frame"
            else -> "direction changing"
        }
    }

    private fun cueEvidenceLabel(sources: String, mirageDetected: Boolean): String {
        val normalized = sources.replace("+", " • ").replace(",", " • ").trim()
        return buildString {
            if (normalized.isNotBlank() && normalized != "NONE") append(normalized)
            if (mirageDetected && !normalized.contains("MIRAGE", ignoreCase = true)) {
                if (isNotEmpty()) append(" • ")
                append("MIRAGE")
            }
            if (isEmpty()) append("VISUAL WIND CUES")
        }
    }

    private fun directionArrow(clock: String): String {
        val hour = clock.substringBefore(" ").toIntOrNull() ?: return "↔"
        return when (hour) {
            12 -> "↑"
            1, 2 -> "↗"
            3 -> "→"
            4, 5 -> "↘"
            6 -> "↓"
            7, 8 -> "↙"
            9 -> "←"
            10, 11 -> "↖"
            else -> "↔"
        }
    }

    private fun setupOpticalModeControls() {
        fun updateButton() {
            binding.opticalModeButton.text = when (opticalOverride) {
                OpticalModeDetector.Override.AUTO -> "MODE AUTO"
                OpticalModeDetector.Override.PHONE -> "MODE PHONE"
                OpticalModeDetector.Override.SPOTTING_SCOPE -> "MODE SCOPE"
            }
        }
        updateButton()
        binding.opticalModeButton.setOnClickListener {
            opticalOverride = when (opticalOverride) {
                OpticalModeDetector.Override.AUTO -> OpticalModeDetector.Override.PHONE
                OpticalModeDetector.Override.PHONE -> OpticalModeDetector.Override.SPOTTING_SCOPE
                OpticalModeDetector.Override.SPOTTING_SCOPE -> OpticalModeDetector.Override.AUTO
            }
            frameAnalyzer?.setOpticalModeOverride(opticalOverride)
            publishedWindResult = null
            lastWindPublishMs = 0L
            binding.feedbackPanel.visibility = View.GONE
            updateButton()
        }
    }

    private fun setupCloudUsageControls() {
        binding.cloudUsageButton.setOnClickListener {
            updateCloudUsageUi()
            binding.cloudUsagePanel.visibility = View.VISIBLE
        }
        binding.closeCloudUsageButton.setOnClickListener {
            binding.cloudUsagePanel.visibility = View.GONE
        }
        binding.openCloudConsoleButton.setOnClickListener {
            val url = "https://console.cloud.google.com/billing/01FCB2-750901-4F0DA2?project=cs-project-6rfoyr6d&organizationId=571526312308"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Throwable) {
                Toast.makeText(this, "Could not open Google Cloud Console.", Toast.LENGTH_SHORT).show()
            }
        }
        updateCloudUsageUi()
    }

    private fun updateCloudUsageUi() {
        if (!::binding.isInitialized) return
        val analyzer = frameAnalyzer
        if (analyzer == null) {
            binding.cloudCounterText.text = "CLOUD -- SESSION • --/30 TODAY • --/900 MONTH"
            binding.cloudUsageText.text = "Cloud AI initializes with the camera.\nDaily limit: 30\nMonthly device limit: 900\nEstimated device cost: €0.00"
            return
        }
        val q = analyzer.cloudQuota()
        val configured = analyzer.cloudConfigured()
        val last = analyzer.latestCloudPrediction()
        binding.cloudCounterText.text = "CLOUD ${q.sessionUsed} SESSION • ${q.dayUsed}/${q.dayLimit} TODAY • ${q.monthUsed}/${q.monthLimit} MONTH"
        val status = if (configured) "READY" else "DISABLED — API KEY NOT IN BUILD"
        val lastText = last?.let { "${it.label} (${(it.confidence * 100).toInt()}%)" } ?: "none yet"
        binding.cloudUsageText.text = buildString {
            appendLine("Cloud AI: $status")
            appendLine("Session requests: ${q.sessionUsed}")
            appendLine("Today: ${q.dayUsed} / ${q.dayLimit}")
            appendLine("This device this month: ${q.monthUsed} / ${q.monthLimit}")
            appendLine("Last cloud label: $lastText")
            append("Estimated device cost: €0.00 while project remains inside Google's free allowance")
        }
    }

    private fun setupFeedbackControls() {
        binding.feedbackAgree.setOnClickListener { recordOperatorFeedback(UserFeedbackLogger.Vote.AGREE) }
        binding.feedbackUnsure.setOnClickListener { recordOperatorFeedback(UserFeedbackLogger.Vote.NOT_SURE) }
        binding.feedbackDisagree.setOnClickListener { recordOperatorFeedback(UserFeedbackLogger.Vote.DISAGREE) }
    }

    private fun recordOperatorFeedback(vote: UserFeedbackLogger.Vote) {
        val file = feedbackLogger.record(vote, publishedWindResult ?: latestAnalysisResult)
        lastFeedbackVotePublishMs = lastWindPublishMs
        binding.feedbackPanel.visibility = View.GONE
        val label = when (vote) {
            UserFeedbackLogger.Vote.AGREE -> "AGREE"
            UserFeedbackLogger.Vote.NOT_SURE -> "NOT SURE"
            UserFeedbackLogger.Vote.DISAGREE -> "DISAGREE"
        }
        Toast.makeText(this, "$label recorded for testing", Toast.LENGTH_SHORT).show()
    }

    private fun setupRangeAssist() {
        binding.autoRangeToggle.isChecked = true
        binding.seaLevelToggle.isChecked = false
        binding.knownRangeInput.isEnabled = false
        binding.autoRangeToggle.setOnCheckedChangeListener { _, auto ->
            binding.knownRangeInput.isEnabled = !auto
            binding.seaLevelToggle.isEnabled = auto
            updateNavigationReadout()
        }
        binding.seaLevelToggle.setOnCheckedChangeListener { _, _ -> updateNavigationReadout() }

        sensorManager = getSystemService(SensorManager::class.java)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationSensor?.let { sensorManager.registerListener(orientationListener, it, SensorManager.SENSOR_DELAY_UI) }
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        lightSensor?.let { sensorManager.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        locationManager = getSystemService(LocationManager::class.java)
    }

    private fun startLocationUpdates() {
        locationEnabled = true
        if (::binding.isInitialized) binding.locationButton.text = "GPS ON"
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        try {
            val lm = locationManager ?: return
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (provider in providers) {
                if (lm.isProviderEnabled(provider)) {
                    lm.getLastKnownLocation(provider)?.let { loc ->
                        if (lastLocation == null || loc.time > (lastLocation?.time ?: 0L)) lastLocation = loc
                    }
                    lm.requestLocationUpdates(provider, 2_000L, 2f, locationListener)
                }
            }
        } catch (_: SecurityException) { }
    }


    private fun stopLocationUpdates() {
        try { locationManager?.removeUpdates(locationListener) } catch (_: SecurityException) { }
        lastLocation = null
        updateNavigationReadout()
    }

    private fun currentRangeContext(): RangeEstimator.Context {
        val manual = if (!binding.autoRangeToggle.isChecked) binding.knownRangeInput.text.toString().toDoubleOrNull() else null
        val fov = binding.scopeFovInput.text.toString().toDoubleOrNull()?.coerceIn(0.05, 20.0) ?: 2.0
        return RangeEstimator.Context(
            manualRangeM = manual,
            autoRangeEnabled = binding.autoRangeToggle.isChecked,
            cameraPitchDeg = cameraPitchDeg,
            gpsAltitudeM = lastLocation?.takeIf { it.hasAltitude() }?.altitude,
            cameraHeightM = 1.5,
            autoPlane = if (binding.seaLevelToggle.isChecked) RangeEstimator.AutoPlane.SEA_LEVEL else RangeEstimator.AutoPlane.GROUND,
            scopeHorizontalFovDeg = fov,
            phoneZoomRatio = if (::cameraController.isInitialized) cameraController.currentZoomRatio().toDouble() else 1.0
        )
    }

    private fun updateNavigationReadout(precomputed: RangeEstimator.DistanceEstimate? = null) {
        if (!::binding.isInitialized) return
        val range = precomputed ?: RangeEstimator.distance(currentRangeContext())
        val mag = cameraHeadingDeg
        val trueAz = mag?.let { normalizeDeg(it + magneticDeclinationDeg()) }
        val compass = trueAz?.let { compassPoint(it) } ?: "--"
        val azText = trueAz?.let { "%03.0f°".format(it) } ?: "--"
        val pitchText = cameraPitchDeg?.let { "%+.1f°".format(it) } ?: "--"
        val rangeText = range.meters?.let { "%.0fm".format(it) } ?: "--"

        binding.navRangeText.text = "AZ $azText  ANGLE $pitchText  RANGE $rangeText"
        binding.targetHudText.text = "AZ $azText  $compass   ANGLE $pitchText   RANGE $rangeText"
    }

    private fun magneticDeclinationDeg(): Double {
        val loc = lastLocation ?: return 0.0
        val altitude = if (loc.hasAltitude()) loc.altitude.toFloat() else 0f
        return GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(), altitude, System.currentTimeMillis()
        ).declination.toDouble()
    }

    private fun normalizeDeg(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    private fun compassPoint(deg: Double): String {
        val points = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val index = ((normalizeDeg(deg) + 11.25) / 22.5).toInt() % 16
        return points[index]
    }

    private fun setupDisplayControls() {
        binding.targetHudToggle.isChecked = true
        binding.targetHudToggle.setOnCheckedChangeListener { _, checked ->
            binding.targetHudText.visibility = if (checked) View.VISIBLE else View.GONE
        }

        binding.autoBrightnessToggle.isChecked = true
        binding.brightnessSeek.max = 100
        binding.brightnessSeek.progress = manualBrightnessPercent
        binding.brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress.coerceIn(10, 100)
                binding.brightnessPercent.text = "$pct%"
                if (fromUser) {
                    manualBrightnessPercent = pct
                    if (autoBrightnessEnabled) binding.autoBrightnessToggle.isChecked = false
                    applyBrightnessPercent(pct)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.autoBrightnessToggle.setOnCheckedChangeListener { _, checked ->
            autoBrightnessEnabled = checked
            binding.brightnessSeek.isEnabled = !checked
            if (checked) applyAutoBrightness(lastAmbientLux ?: 500f)
            else applyBrightnessPercent(manualBrightnessPercent)
        }
        binding.brightnessSeek.isEnabled = false
        applyAutoBrightness(lastAmbientLux ?: 500f)
    }

    private fun applyAutoBrightness(lux: Float) {
        val requested = when {
            lux < 5f -> 30
            lux < 50f -> 42
            lux < 500f -> 58
            lux < 5_000f -> 78
            lux < 20_000f -> 90
            else -> 100
        }
        val applied = thermallyCappedBrightness(requested)
        applyBrightnessPercent(applied)
        if (::binding.isInitialized) {
            binding.brightnessSeek.progress = applied
            binding.brightnessPercent.text = "$applied% AUTO"
        }
    }

    private fun thermallyCappedBrightness(requested: Int): Int {
        val bt = batteryTempC ?: 0.0
        if (bt >= 37.0) return requested.coerceAtMost(55)
        if (bt >= 36.0) return requested.coerceAtMost(70)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return requested.coerceAtMost(40)
            if (currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return requested.coerceAtMost(70)
        }
        return requested.coerceIn(10, 100)
    }

    private fun applyBrightnessPercent(percent: Int) {
        val applied = thermallyCappedBrightness(percent)
        val lp = window.attributes
        lp.screenBrightness = (applied / 100f).coerceIn(0.1f, 1f)
        window.attributes = lp
        if (::binding.isInitialized && !autoBrightnessEnabled) {
            binding.brightnessPercent.text = if (applied == percent) "$applied%" else "$applied% HEAT CAP"
        }
    }

    private fun refreshBrightnessForThermalState() {
        if (!::binding.isInitialized) return
        if (autoBrightnessEnabled) applyAutoBrightness(lastAmbientLux ?: 500f)
        else applyBrightnessPercent(manualBrightnessPercent)
    }

    private fun analysisRoiToView(r: AnalysisResult): RectF {
        val vw = binding.previewView.width.toFloat(); val vh = binding.previewView.height.toFloat()
        if (vw <= 0 || vh <= 0) return binding.roiOverlay.roiInViewCoords()
        val scale = max(vw / r.fullWidthPx.toFloat(), vh / r.fullHeightPx.toFloat())
        val ox = (vw - r.fullWidthPx * scale) / 2f; val oy = (vh - r.fullHeightPx * scale) / 2f
        return RectF(ox + r.roiLeftPx * scale, oy + r.roiTopPx * scale,
            ox + (r.roiLeftPx + r.roiWidthPx) * scale, oy + (r.roiTopPx + r.roiHeightPx) * scale)
    }

    private fun setupPinchZoom() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!::cameraController.isInitialized) return false
                if (autoZoomEnabled) binding.autoZoomToggle.isChecked = false
                val (minZ,maxZ)=cameraController.zoomRange()
                val next=(cameraController.currentZoomRatio()*detector.scaleFactor).coerceIn(minZ,maxZ)
                cameraController.setZoomRatio(next)
                updateZoomText()
                frameAnalyzer?.resetForNewRoi()
                return true
            }
        })
        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            true
        }
    }

    private fun startVoiceZoom() {
        try {
            val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say increase magnification or decrease magnification")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            voiceZoomLauncher.launch(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "Voice recognition is not available on this phone.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun beginAutoZoomSweep() {
        if (!autoZoomEnabled || waitingForManualResume) return
        val (minZoom, maxZoom) = cameraController.zoomRange()
        val desired = listOf(1f, 1.4f, 2f, 3f, 4f)
            .map { it.coerceIn(minZoom, maxZoom) }.distinct().sorted()
        if (desired.isEmpty()) return
        zoomCandidates = desired; zoomCandidateIndex = 0; zoomResults.clear()
        autoZoomSearching = true; applyZoomCandidate()
    }

    private fun applyZoomCandidate() {
        if (!autoZoomSearching || zoomCandidateIndex !in zoomCandidates.indices) return
        val z = zoomCandidates[zoomCandidateIndex]
        cameraController.setZoomRatio(z)
        zoomScoreSum = 0.0; zoomScoreCount = 0; zoomCandidateStartedMs = SystemClock.elapsedRealtime()
        binding.zoomText.text = "AUTO SEARCH %.1fx".format(z)
        frameAnalyzer?.resetForNewRoi()
    }

    private fun handleAutoZoom(r: AnalysisResult) {
        if (!autoZoomEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (autoZoomSearching) {
            // Give the camera ~600 ms to settle before scoring this magnification.
            if (now - zoomCandidateStartedMs > 600L) {
                val cueBonus = if (r.motionCues.isNotEmpty()) 0.08 else 0.0
                zoomScoreSum += (r.signalScore + cueBonus).coerceIn(0.0, 1.0)
                zoomScoreCount++
            }
            if (now - zoomCandidateStartedMs >= 1500L && zoomScoreCount >= 3) {
                val z = zoomCandidates[zoomCandidateIndex]
                zoomResults[z] = zoomScoreSum / zoomScoreCount.coerceAtLeast(1)
                zoomCandidateIndex++
                if (zoomCandidateIndex < zoomCandidates.size) applyZoomCandidate()
                else {
                    val best = zoomResults.maxByOrNull { it.value }?.key ?: 1f
                    cameraController.setZoomRatio(best)
                    autoZoomSearching = false
                    nextAutoZoomSweepMs = now + if (currentSignalScore < 0.25) 12_000L else 30_000L
                    updateZoomText()
                    frameAnalyzer?.resetForNewRoi()
                }
            }
        } else if (now >= nextAutoZoomSweepMs && r.signalScore < 0.22) {
            beginAutoZoomSweep()
        }
    }

    private fun manualZoom(direction: Int) {
        if (!::cameraController.isInitialized) return
        if (autoZoomEnabled) {
            binding.autoZoomToggle.isChecked = false // listener disables auto
        }
        val current = cameraController.currentZoomRatio()
        val (minZ, maxZ) = cameraController.zoomRange()
        val factor = if (direction > 0) 1.25f else 0.8f
        cameraController.setZoomRatio((current * factor).coerceIn(minZ, maxZ))
        mainHandler.postDelayed({ updateZoomText(); frameAnalyzer?.resetForNewRoi() }, 150L)
    }

    private fun updateZoomText() {
        if (!::cameraController.isInitialized) return
        binding.zoomText.text = "%.2fx %s".format(cameraController.currentZoomRatio(), if (autoZoomEnabled) "AUTO" else "MAN")
    }

    private fun toggleRecording() {
        if (cameraController.isRecording()) {
            cameraController.stopRecording(); stopStorageChecks(); binding.recordButton.text = "RECORD"; return
        }
        val freeBytes = availableStorageBytes()
        if (freeBytes in 0 until minFreeBytesToStartRecording) {
            Toast.makeText(this, "Recording not started: keep at least 500 MB free.", Toast.LENGTH_LONG).show(); return
        }
        val recorder = SessionRecorder(this); sessionRecorder = recorder
        cameraController.startRecordingToGallery { success, error, savedLocation ->
            recorder.finish(success, error)
            runOnUiThread {
                stopStorageChecks(); binding.recordButton.text = "RECORD"
                Toast.makeText(this, if (success) "Video saved in Camera: ${savedLocation ?: "Mirage recording"}" else "Recording error: $error", Toast.LENGTH_LONG).show()
            }
            sessionRecorder = null
        }
        binding.recordButton.text = "STOP"; startStorageChecks()
    }
}
