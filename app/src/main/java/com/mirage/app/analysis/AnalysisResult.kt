package com.mirage.app.analysis

/** A single flow/displacement arrow, in ROI-LOCAL pixel coordinates, for debug drawing only. */
data class VectorArrow(val x: Float, val y: Float, val dx: Float, val dy: Float, val response: Float = 1f)

/** A visually detected moving environmental cue in FULL ANALYSIS FRAME coordinates. */
data class MotionCue(
    val label: String,
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
    /** Direction the visible object/material is moving, expressed as a clock position. */
    val clockDirection: String,
    /** Image-plane direction, 0=12 o'clock, 90=3 o'clock, 180=6 o'clock, 270=9 o'clock. */
    val clockAngleDeg: Double,
    val apparentSpeedPxPerSec: Double,
    /** Experimental visual estimate only; deliberately a range rather than false precision. */
    val estimatedWindMinMps: Double,
    val estimatedWindMaxMps: Double,
    val confidence: Double,
    val stable: Boolean,
    val stableForSec: Double,
    /** Range used for the physical-motion conversion, when available. */
    val distanceM: Double? = null,
    val distanceSource: String = "UNKNOWN",
    /** Apparent transverse speed at the estimated/known range. Not automatically equal to wind speed. */
    val physicalMotionMps: Double? = null,
    val windEstimateQuality: String = "VISUAL ONLY"
)

/** Everything produced by one pass of the live visual-wind pipeline. */
data class AnalysisResult(
    val cameraFrameTimestampNs: Long,
    val wallClockElapsedRealtimeNs: Long,
    val frameIndex: Long,

    val fullWidthPx: Int,
    val fullHeightPx: Int,
    val roiLeftPx: Int,
    val roiTopPx: Int,
    val roiWidthPx: Int,
    val roiHeightPx: Int,

    // stabilization
    val stabDx: Double,
    val stabDy: Double,
    val stabRotationDeg: Double,
    val stabDisturbance: Boolean,
    val stabTrackingOk: Boolean,

    // 1. optical flow
    val ofAngleDeg: Double,
    val ofMagnitude: Double,
    val ofMeanMagnitude: Double,
    val ofActiveFraction: Double,
    val flowVectors: List<VectorArrow>,

    // 2. block matching
    val bmAngleDeg: Double,
    val bmMagnitude: Double,
    val bmMeanMagnitude: Double,
    val bmAngularDispersion: Double,
    val bmMeanResponse: Double,
    val bmInlierFraction: Double,
    val blockVectors: List<VectorArrow>,

    // 3. texture variance
    val tvActivityIndex: Double,
    val tvSpatialTexture: Double,

    // 4. frequency domain
    val fdDominantFreqHz: Double,
    val fdSpectralEnergy: Double,
    val fdSpectralCentroidHz: Double,

    // Mirage interpretation
    val sufficientSignal: Boolean,
    val mirageClockDirection: String,
    val mirageStable: Boolean,
    val mirageStableForSec: Double,
    /** 0..1 composite score used by AUTO ZOOM to choose the clearest view. */
    val signalScore: Double,
    val sceneLuma: Double = 128.0,

    // Secondary visual wind indicators
    val motionCues: List<MotionCue>,

    // Experimental centre-target interpretation. Labels are deliberately conservative.
    val targetLabel: String = "",
    val targetConfidence: Double = 0.0,
    val targetLeftPx: Int = 0,
    val targetTopPx: Int = 0,
    val targetRightPx: Int = 0,
    val targetBottomPx: Int = 0
)
