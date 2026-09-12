package com.mirage.app.analysis

import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Rect as CvRect
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Live visual-wind pipeline.
 *
 * v0.3 adds:
 *  - AUTO ROI: periodically searches the frame for the strongest usable atmospheric signal.
 *  - secondary moving-cue detection across the whole stabilized frame.
 *  - clock-direction and stability interpretation.
 *  - a 0..1 signal score used by AUTO ZOOM.
 */
class FrameAnalyzer(
    targetHz: Double,
    private val roiSupplier: (analysisWidth: Int, analysisHeight: Int) -> Rect,
    private val onResult: (AnalysisResult, ImageProxy) -> Unit
) : androidx.camera.core.ImageAnalysis.Analyzer {

    private val stabilizer = Stabilizer()
    private val opticalFlow = OpticalFlowExtractor()
    private val blockMatching = BlockMatchingExtractor()
    private val textureVariance = TextureVarianceExtractor()
    private val frequencyDomain = FrequencyDomainExtractor()
    private val motionCueDetector = MotionCueDetector()
    private val centerTargetDetector = CenterTargetDetector()

    private var lastAnalyzedNs = 0L
    @Volatile private var targetHz: Double = targetHz
    @Volatile private var minIntervalNs = intervalForHz(targetHz)
    @Volatile private var autoRoiEnabled = true
    private var frameIndex = 0L

    private val minSpatialTexture = 50.0
    private val minActivityIndex = 0.05

    private var autoRoi: Rect? = null
    private var autoSearchReference: Mat? = null
    private var lastAutoSearchFrame = -100L
    private var weakSignalFrames = 0

    // Mirage stability tracker. Direction is derived from trusted block vectors without folding to 0..180.
    private var mirageStableSinceNs = 0L
    private var lastMirageClockAngle = Double.NaN
    private var lastMirageMagnitude = Double.NaN

    fun setTargetHz(hz: Double) {
        val safeHz = hz.coerceIn(1.0, 30.0)
        targetHz = safeHz
        minIntervalNs = intervalForHz(safeHz)
    }

    fun currentTargetHz(): Double = targetHz
    fun setAutoRoiEnabled(enabled: Boolean) {
        autoRoiEnabled = enabled
        if (!enabled) autoRoi = null
        resetForNewRoi()
    }

    private fun intervalForHz(hz: Double): Long = (1_000_000_000.0 / hz).toLong()

    fun resetForNewRoi() {
        opticalFlow.reset()
        blockMatching.reset()
        textureVariance.reset()
        frequencyDomain.reset()
        mirageStableSinceNs = 0L
        lastMirageClockAngle = Double.NaN
        lastMirageMagnitude = Double.NaN
    }

    override fun analyze(image: ImageProxy) {
        val cameraTimestampNs = image.imageInfo.timestamp
        if (cameraTimestampNs - lastAnalyzedNs < minIntervalNs) {
            image.close(); return
        }
        lastAnalyzedNs = cameraTimestampNs

        var fullGray: Mat? = null
        var rotated: Mat? = null
        var roiMat: Mat? = null
        var roiFloat: Mat? = null
        try {
            fullGray = imageProxyToGrayMat(image)
            rotated = rotateToUpright(fullGray, image.imageInfo.rotationDegrees)
            val (stabilizedU8, stabInfo) = stabilizer.process(rotated)

            // Secondary cues always scan the full stabilized scene, independent of the mirage ROI.
            val motionCues = if (!stabInfo.disturbance && stabInfo.trackingOk) {
                motionCueDetector.process(stabilizedU8, cameraTimestampNs)
            } else emptyList()

            // Search periodically at startup / while signal is weak. This prevents constant ROI hunting.
            if (autoRoiEnabled &&
                (autoRoi == null || weakSignalFrames >= 8) &&
                frameIndex - lastAutoSearchFrame >= 10
            ) {
                findBestAtmosphericRoi(stabilizedU8)?.let { candidate ->
                    if (autoRoi == null || rectDistance(autoRoi!!, candidate) > 20) {
                        autoRoi = candidate
                        resetForNewRoi()
                    }
                }
                lastAutoSearchFrame = frameIndex
            }

            val roi = sanitizeRoi(
                if (autoRoiEnabled) autoRoi ?: defaultCentralRoi(stabilizedU8.cols(), stabilizedU8.rows())
                else roiSupplier(stabilizedU8.cols(), stabilizedU8.rows()),
                stabilizedU8.cols(), stabilizedU8.rows()
            )

            roiMat = Mat(stabilizedU8, CvRect(roi.left, roi.top, roi.width(), roi.height())).clone()
            roiFloat = Mat()
            roiMat.convertTo(roiFloat, CvType.CV_32F)

            val ofResult = opticalFlow.process(roiMat)
            val bmResult = blockMatching.process(roiFloat)
            val tvResult = textureVariance.process(roiFloat)
            val fdResult = frequencyDomain.process(roiFloat, roiFloat.cols() / 2)

            val sufficientSignal = tvResult.spatialTexture > minSpatialTexture &&
                tvResult.activityIndex > minActivityIndex && !stabInfo.disturbance
            weakSignalFrames = if (sufficientSignal) 0 else weakSignalFrames + 1

            val signalScore = computeSignalScore(tvResult.spatialTexture, tvResult.activityIndex,
                bmResult.inlierFraction, bmResult.angularDispersion)

            val mirageClockAngle = deriveClockAngleFromVectors(bmResult.vectors)
            val mirageClock = if (mirageClockAngle.isFinite()) MotionCueDetector.clockLabel(mirageClockAngle) else "--"
            val (mirageStable, mirageStableFor) = updateMirageStability(
                sufficientSignal, mirageClockAngle, bmResult.magnitude, cameraTimestampNs
            )

            val target = if (!stabInfo.disturbance) centerTargetDetector.detect(stabilizedU8) else null

            val result = AnalysisResult(
                cameraFrameTimestampNs = cameraTimestampNs,
                wallClockElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                frameIndex = frameIndex++,
                fullWidthPx = stabilizedU8.cols(),
                fullHeightPx = stabilizedU8.rows(),
                roiLeftPx = roi.left,
                roiTopPx = roi.top,
                roiWidthPx = roi.width(),
                roiHeightPx = roi.height(),
                stabDx = stabInfo.stepTransform.dx,
                stabDy = stabInfo.stepTransform.dy,
                stabRotationDeg = stabInfo.stepTransform.rotationDeg,
                stabDisturbance = stabInfo.disturbance,
                stabTrackingOk = stabInfo.trackingOk,
                ofAngleDeg = ofResult.angleDeg,
                ofMagnitude = ofResult.magnitude,
                ofMeanMagnitude = ofResult.meanMagnitude,
                ofActiveFraction = ofResult.activeFraction,
                flowVectors = ofResult.vectors,
                bmAngleDeg = bmResult.angleDeg,
                bmMagnitude = bmResult.magnitude,
                bmMeanMagnitude = bmResult.meanMagnitude,
                bmAngularDispersion = bmResult.angularDispersion,
                bmMeanResponse = bmResult.meanResponse,
                bmInlierFraction = bmResult.inlierFraction,
                blockVectors = bmResult.vectors,
                tvActivityIndex = tvResult.activityIndex,
                tvSpatialTexture = tvResult.spatialTexture,
                fdDominantFreqHz = fdResult.dominantFreqHz,
                fdSpectralEnergy = fdResult.spectralEnergy,
                fdSpectralCentroidHz = fdResult.spectralCentroidHz,
                sufficientSignal = sufficientSignal,
                mirageClockDirection = mirageClock,
                mirageStable = mirageStable,
                mirageStableForSec = mirageStableFor,
                signalScore = signalScore,
                sceneLuma = Core.mean(stabilizedU8).`val`[0],
                motionCues = motionCues,
                targetLabel = target?.label ?: "",
                targetConfidence = target?.confidence ?: 0.0,
                targetLeftPx = target?.rect?.x ?: 0,
                targetTopPx = target?.rect?.y ?: 0,
                targetRightPx = target?.rect?.let { it.x + it.width } ?: 0,
                targetBottomPx = target?.rect?.let { it.y + it.height } ?: 0
            )
            onResult(result, image)

            stabilizedU8.release()
        } finally {
            fullGray?.release(); rotated?.release(); roiMat?.release(); roiFloat?.release()
            image.close()
        }
    }

    private fun updateMirageStability(
        sufficient: Boolean, angle: Double, magnitude: Double, nowNs: Long
    ): Pair<Boolean, Double> {
        if (!sufficient || !angle.isFinite() || !magnitude.isFinite()) {
            mirageStableSinceNs = nowNs
            lastMirageClockAngle = angle
            lastMirageMagnitude = magnitude
            return false to 0.0
        }
        if (mirageStableSinceNs == 0L) mirageStableSinceNs = nowNs
        val angleOk = !lastMirageClockAngle.isFinite() || angularDistance(angle, lastMirageClockAngle) <= 25.0
        val magOk = !lastMirageMagnitude.isFinite() || lastMirageMagnitude < 0.02 ||
            magnitude / lastMirageMagnitude.coerceAtLeast(0.02) in 0.60..1.65
        if (!angleOk || !magOk) mirageStableSinceNs = nowNs
        lastMirageClockAngle = angle
        lastMirageMagnitude = magnitude
        val seconds = (nowNs - mirageStableSinceNs).coerceAtLeast(0L) / 1_000_000_000.0
        return (seconds >= 3.0) to seconds
    }

    private fun deriveClockAngleFromVectors(vectors: List<VectorArrow>): Double {
        val trusted = vectors.filter { it.response >= 0.15f && hypot(it.dx.toDouble(), it.dy.toDouble()) > 0.02 }
        if (trusted.isEmpty()) return Double.NaN
        val dx = median(trusted.map { it.dx.toDouble() })
        val dy = median(trusted.map { it.dy.toDouble() })
        return MotionCueDetector.vectorToClockAngle(dx, dy)
    }

    private fun computeSignalScore(texture: Double, activity: Double, inliers: Double, dispersion: Double): Double {
        val t = (texture / 250.0).coerceIn(0.0, 1.0)
        val a = (activity / 0.35).coerceIn(0.0, 1.0)
        val i = inliers.coerceIn(0.0, 1.0)
        val coherence = if (dispersion.isFinite()) (1.0 - dispersion).coerceIn(0.0, 1.0) else 0.0
        return (0.25 * t + 0.35 * a + 0.20 * i + 0.20 * coherence).coerceIn(0.0, 1.0)
    }

    /** Search a 4x3 grid for textured, temporally active regions; returns a larger ROI around the best tile. */
    private fun findBestAtmosphericRoi(gray: Mat): Rect? {
        val ref = autoSearchReference
        if (ref == null || ref.size() != gray.size()) {
            autoSearchReference?.release()
            autoSearchReference = gray.clone()
            return null
        }
        val diff = Mat(); Core.absdiff(gray, ref, diff)
        val cols = 4; val rows = 3
        var bestScore = Double.NEGATIVE_INFINITY
        var bestCx = gray.cols() / 2
        var bestCy = gray.rows() / 2
        val tileW = gray.cols() / cols
        val tileH = gray.rows() / rows
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = c * tileW; val y = r * tileH
            val w = if (c == cols - 1) gray.cols() - x else tileW
            val h = if (r == rows - 1) gray.rows() - y else tileH
            if (w < 16 || h < 16) continue
            val rect = CvRect(x, y, w, h)
            val tile = Mat(gray, rect); val d = Mat(diff, rect)
            val lap = Mat(); Imgproc.Laplacian(tile, lap, CvType.CV_32F)
            val mean = MatOfDouble(); val std = MatOfDouble(); Core.meanStdDev(lap, mean, std)
            val texture = std.toArray().firstOrNull() ?: 0.0
            val activity = Core.mean(d).`val`[0]
            // Mirage usually benefits from some texture + subtle activity; huge activity is likely an object.
            val objectPenalty = if (activity > 35.0) (activity - 35.0) * 1.5 else 0.0
            val score = texture * 0.55 + activity * 1.8 - objectPenalty
            if (score > bestScore) { bestScore = score; bestCx = x + w / 2; bestCy = y + h / 2 }
            lap.release(); mean.release(); std.release()
        }
        diff.release()
        autoSearchReference?.release(); autoSearchReference = gray.clone()

        val roiW = (gray.cols() * 0.48).toInt().coerceAtLeast(120)
        val roiH = (gray.rows() * 0.48).toInt().coerceAtLeast(90)
        val left = (bestCx - roiW / 2).coerceIn(0, gray.cols() - roiW)
        val top = (bestCy - roiH / 2).coerceIn(0, gray.rows() - roiH)
        return Rect(left, top, left + roiW, top + roiH)
    }

    private fun defaultCentralRoi(w: Int, h: Int): Rect {
        val rw = (w * 0.55).toInt(); val rh = (h * 0.55).toInt()
        val l = (w - rw) / 2; val t = (h - rh) / 2
        return Rect(l, t, l + rw, t + rh)
    }

    private fun sanitizeRoi(r: Rect, w: Int, h: Int): Rect {
        val left = r.left.coerceIn(0, w - 2)
        val top = r.top.coerceIn(0, h - 2)
        val right = r.right.coerceIn(left + 1, w)
        val bottom = r.bottom.coerceIn(top + 1, h)
        return Rect(left, top, right, bottom)
    }

    private fun rectDistance(a: Rect, b: Rect): Int =
        abs(a.centerX() - b.centerX()) + abs(a.centerY() - b.centerY())

    private fun median(values: List<Double>): Double {
        val s = values.sorted(); val m = s.size / 2
        return if (s.size % 2 == 0) (s[m - 1] + s[m]) / 2.0 else s[m]
    }

    private fun angularDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return min(d, 360.0 - d)
    }

    private fun imageProxyToGrayMat(image: ImageProxy): Mat {
        val yPlane = image.planes[0]
        val rowStride = yPlane.rowStride
        val width = image.width
        val height = image.height
        val buffer = yPlane.buffer
        val mat = Mat(height, width, CvType.CV_8UC1)
        if (rowStride == width) {
            val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); mat.put(0, 0, bytes)
        } else {
            val rowBytes = ByteArray(rowStride)
            for (row in 0 until height) {
                buffer.position(row * rowStride); buffer.get(rowBytes, 0, rowStride)
                mat.put(row, 0, rowBytes.copyOf(width))
            }
        }
        return mat
    }

    private fun rotateToUpright(mat: Mat, rotationDegrees: Int): Mat {
        val out = Mat()
        when (rotationDegrees) {
            90 -> Core.rotate(mat, out, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(mat, out, Core.ROTATE_180)
            270 -> Core.rotate(mat, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return mat.clone()
        }
        return out
    }
}
