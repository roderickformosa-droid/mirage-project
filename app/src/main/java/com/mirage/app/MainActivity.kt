package com.mirage.app

import android.content.pm.PackageManager
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.core.content.ContextCompat
import com.mirage.app.analysis.AnalysisResult
import com.mirage.app.analysis.CoordinateMapper
import com.mirage.app.analysis.FrameAnalyzer
import com.mirage.app.analysis.RangeEstimator
import com.mirage.app.camera.CameraController
import com.mirage.app.databinding.ActivityMainBinding
import com.mirage.app.logging.SessionRecorder
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraController
    private var frameAnalyzer: FrameAnalyzer? = null
    private var sessionRecorder: SessionRecorder? = null

    // RANGE ASSIST: orientation is always local-only; location is optional and used only for
    // sea-level intersection. No internet permission is declared.
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

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[android.Manifest.permission.CAMERA] == true) initCameraOnceOpenCvReady()
        else Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
        if (grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) startLocationUpdates()
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

        setupRangeAssist()
        setupDisplayControls()

        val needed = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            initCameraOnceOpenCvReady(); startLocationUpdates()
        } else requestPermissions.launch(needed.toTypedArray())

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
        waitingForManualResume = false; thermalShutdown = false
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
        cancelSessionTimeout(); stopStorageChecks()
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
            setupCameraSpinner(); attachAnalyzer(analysis); scheduleSessionTimeout()
            mainHandler.postDelayed({ if (autoZoomEnabled) beginAutoZoomSweep() }, 900L)
        }
    }

    private fun setupCameraSpinner() {
        val options = cameraController.listRearCameras()
        binding.cameraSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.label })
        if (options.isNotEmpty()) binding.cameraSpinner.setSelection(0)
        binding.cameraSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (waitingForManualResume || position !in options.indices) return
                cameraController.startCamera(binding.previewView, options[position], analysisTargetSize) { analysis, _, _ ->
                    attachAnalyzer(analysis)
                    mainHandler.postDelayed({ if (autoZoomEnabled) beginAutoZoomSweep() }, 700L)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun attachAnalyzer(analysis: ImageAnalysis) {
        val initialHz = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) reducedAnalysisHz else normalAnalysisHz
        val analyzer = FrameAnalyzer(
            targetHz = initialHz,
            roiSupplier = { analysisW, analysisH ->
                val mapper = CoordinateMapper(binding.previewView.width, binding.previewView.height, analysisW, analysisH)
                mapper.viewRectToAnalysisRect(binding.roiOverlay.roiInViewCoords())
            },
            onResult = { result, _ -> runOnUiThread { onAnalysisResult(result) } }
        )
        analyzer.setAutoRoiEnabled(binding.autoRoiToggle.isChecked)
        frameAnalyzer = analyzer
        analysis.setAnalyzer(cameraController.analysisExecutor, analyzer)
        binding.roiOverlay.onRoiChanged = { frameAnalyzer?.resetForNewRoi() }
    }

    private fun onAnalysisResult(raw: AnalysisResult) {
        currentSignalScore = raw.signalScore

        val rangeCtx = currentRangeContext()
        val enrichedCues = raw.motionCues.map {
            RangeEstimator.enrich(it, raw.fullWidthPx, rangeCtx)
        }
        val r = raw.copy(motionCues = enrichedCues)
        binding.windCueOverlay.update(r)

        val range = RangeEstimator.distance(rangeCtx)
        val bestCue = r.motionCues.maxByOrNull { it.confidence }
        binding.readoutText.text = buildString {
            appendLine("MIRAGE: ${if (r.sufficientSignal) r.mirageClockDirection else "insufficient"}")
            appendLine("score %.2f | ${if (r.mirageStable) "STABLE %.1fs".format(r.mirageStableForSec) else "changing"}")
            if (bestCue != null) {
                appendLine("${bestCue.label}: ${bestCue.clockDirection}")
                appendLine("EST wind %.1f–%.1f m/s | conf %.0f%%".format(
                    bestCue.estimatedWindMinMps, bestCue.estimatedWindMaxMps, bestCue.confidence * 100.0))
                bestCue.physicalMotionMps?.let {
                    appendLine("object %.2f m/s @ %.0fm".format(it, bestCue.distanceM ?: 0.0))
                }
                appendLine(bestCue.windEstimateQuality)
            } else appendLine("secondary cues: none")
            appendLine("range ${range.meters?.let { "%.0fm".format(it) } ?: "--"} [${range.source}]")
            appendLine("zoom %.2fx ${if (autoZoomEnabled) "AUTO" else "MANUAL"}".format(cameraController.currentZoomRatio()))
        }

        updateNavigationReadout(range)

        val warm = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val status = when {
            r.mirageStable -> "● STABLE MIRAGE — ${r.mirageClockDirection}"
            bestCue?.stable == true -> "● STABLE ${bestCue.label} — ${bestCue.clockDirection}"
            r.sufficientSignal -> "MIRAGE DETECTED — ${r.mirageClockDirection}"
            bestCue != null -> "VISUAL WIND CUE — ${bestCue.label}"
            else -> "SEARCHING — INSUFFICIENT SIGNAL"
        }
        binding.signalQualityText.text = if (warm) "WARM 3 Hz | $status" else status
        binding.signalQualityText.setBackgroundColor(when {
            r.mirageStable || bestCue?.stable == true -> 0xAA087A22.toInt()
            r.sufficientSignal || bestCue != null -> 0xAA8A6500.toInt()
            else -> 0x88AA0000.toInt()
        })

        binding.graphActivity.addValue(r.tvActivityIndex.toFloat())
        binding.graphAngle.addValue(r.bmAngleDeg.toFloat())
        binding.graphMagnitude.addValue(r.bmMagnitude.toFloat())
        binding.graphStabResidual.addValue(kotlin.math.hypot(r.stabDx, r.stabDy).toFloat())

        if (binding.debugOverlay.visibility == View.VISIBLE) {
            binding.debugOverlay.update(r, analysisRoiToView(r))
        }
        handleAutoZoom(r)
        sessionRecorder?.logResult(r)
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
        val magText = mag?.let { "%03.0f°M".format(it) } ?: "--"
        val azText = trueAz?.let { "%03.0f°T".format(it) } ?: "--"
        val pitchText = cameraPitchDeg?.let { "%+.1f°".format(it) } ?: "--"
        val rangeText = range.meters?.let { "%.0fm".format(it) } ?: "--"

        binding.navRangeText.text = "AZ $azText  INC $pitchText  R $rangeText"
        binding.targetHudText.text = buildString {
            appendLine("AZ $azText  $compass")
            appendLine("MAG $magText")
            appendLine("INC $pitchText")
            append("RNG $rangeText")
        }
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
        cameraController.startRecording(recorder.videoFile) { success, error ->
            recorder.finish(success, error)
            runOnUiThread {
                stopStorageChecks(); binding.recordButton.text = "RECORD"
                Toast.makeText(this, if (success) "Saved: ${recorder.sessionDir}" else "Recording error: $error", Toast.LENGTH_LONG).show()
            }
            sessionRecorder = null
        }
        binding.recordButton.text = "STOP"; startStorageChecks()
    }
}
