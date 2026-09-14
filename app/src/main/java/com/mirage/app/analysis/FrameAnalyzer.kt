package com.mirage.app.analysis

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import org.opencv.core.*
import org.opencv.core.Rect as CvRect
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * v0.68 temporal environmental-observation pipeline.
 * Fast frame analysis stays internal; the UI publishes a calmer wind opinion separately.
 */
class FrameAnalyzer(
    context: Context,
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
    private val manualCueTracker = ManualCueTracker()
    private val cueTracker = CuePersistenceTracker()
    private val opticalModeDetector = OpticalModeDetector()
    private val learningWindow = EvidenceLearningWindow()
    private val aiVision = AiVisionClassifier()
    private val cloudVision = CloudVisionClassifier(context)
    private val windFusion = WindFusionEngine()
    private val reasoningScene = ReasoningSceneAnalyzer(context)
    @Volatile private var knownRangeM: Double? = null

    private var lastAnalyzedNs = 0L
    @Volatile private var targetHz: Double = targetHz
    @Volatile private var minIntervalNs = intervalForHz(targetHz)
    @Volatile private var autoRoiEnabled = true
    private var frameIndex = 0L
    @Volatile private var ambientLux: Float? = null

    private var autoRoi: Rect? = null
    private var autoSearchReference: Mat? = null
    private var lastAutoSearchFrame = -100L
    private var weakSignalFrames = 0
    private var mirageCandidateFrames = 0
    private var mirageStableSinceNs = 0L
    private var lastMirageClockAngle = Double.NaN
    private var lastMirageMagnitude = Double.NaN

    fun setTargetHz(hz: Double) {
        val safeHz = hz.coerceIn(1.0, 30.0)
        targetHz = safeHz
        minIntervalNs = intervalForHz(safeHz)
    }
    fun currentTargetHz(): Double = targetHz
    fun cloudQuota(): CloudVisionClassifier.Quota = cloudVision.quota()
    fun cloudConfigured(): Boolean = cloudVision.isConfigured()
    fun latestCloudPrediction(): CloudVisionClassifier.Prediction? = cloudVision.latest(60_000L)
    fun setAmbientLux(lux: Float?) { ambientLux = lux }
    fun setAutoRoiEnabled(enabled: Boolean) { autoRoiEnabled = enabled; if (!enabled) autoRoi = null; resetForNewRoi() }
    fun setOpticalModeOverride(value: OpticalModeDetector.Override) { opticalModeDetector.setOverride(value); resetForNewRoi() }
    fun opticalModeOverride(): OpticalModeDetector.Override = opticalModeDetector.currentOverride()
    fun setManualTrainingCue(label: String, normalizedX: Double, normalizedY: Double) {
        manualCueTracker.lock(label, normalizedX, normalizedY)
        learningWindow.reset()
    }
    fun clearManualTrainingCue() {
        manualCueTracker.clear()
        learningWindow.reset()
    }
    fun manualTrainingCueLabel(): String? = manualCueTracker.activeLabel()
    fun setKnownRangeMeters(value: Double?) { knownRangeM = value?.takeIf { it > 0.0 } }
    fun reasoningConfigured(): Boolean = reasoningScene.configured()
    private fun intervalForHz(hz: Double): Long = (1_000_000_000.0 / hz).toLong()

    fun resetForNewRoi() {
        opticalFlow.reset(); blockMatching.reset(); textureVariance.reset(); frequencyDomain.reset()
        motionCueDetector.reset(); cueTracker.reset(); learningWindow.reset(); manualCueTracker.resetHistory()
        mirageStableSinceNs = 0L; lastMirageClockAngle = Double.NaN; lastMirageMagnitude = Double.NaN
        mirageCandidateFrames = 0; autoRoi = null
    }

    override fun analyze(image: ImageProxy) {
        val cameraTimestampNs = image.imageInfo.timestamp
        if (cameraTimestampNs - lastAnalyzedNs < minIntervalNs) { image.close(); return }
        lastAnalyzedNs = cameraTimestampNs

        var fullGray: Mat? = null
        var rotated: Mat? = null
        var working: Mat? = null
        var roiMat: Mat? = null
        var roiFloat: Mat? = null
        var stabilizedToRelease: Mat? = null
        try {
            fullGray = imageProxyToGrayMat(image)
            rotated = rotateToUpright(fullGray, image.imageInfo.rotationDegrees)
            val (stabilizedU8, stabInfo) = stabilizer.process(rotated)
            stabilizedToRelease = stabilizedU8

            val optical = opticalModeDetector.detect(stabilizedU8)
            val usable = optical.usableRect
            working = if (optical.mode == OpticalModeDetector.Mode.SPOTTING_SCOPE && usable.width > 40 && usable.height > 40) {
                Mat(stabilizedU8, usable).clone().also { applyScopeMask(it) }
            } else stabilizedU8.clone()
            val sceneLuma = Core.mean(working).`val`[0]

            // Periodic scene-level reasoning stays in the loop throughout the session. It does not
            // replace fast CV; it tells the app which environmental evidence appears meaningful.
            reasoningScene.submitIfDue(working, when (optical.mode) {
                OpticalModeDetector.Mode.SPOTTING_SCOPE -> "SPOTTING SCOPE"
                OpticalModeDetector.Mode.PHONE -> "PHONE CAMERA"
                else -> "DETECTING"
            }, knownRangeM)
            val reasoning = reasoningScene.latest()

            // Environmental cues are analysed immediately and concurrently with mirage.
            // TRAIN mode user-lock bypasses the generic motion/semantic gate completely: once the
            // user taps a cue, motion is measured directly inside that persistent region even when
            // the generic stabilizer/scene tracker says the scope image is not trackable.
            val genericCues = if (stabInfo.trackingOk) motionCueDetector.process(working, cameraTimestampNs) else emptyList()
            val manualCue = manualCueTracker.process(working, cameraTimestampNs)
            var instantaneousCues = if (manualCue != null) listOf(manualCue) + genericCues else genericCues
            val strongestRaw = instantaneousCues.maxByOrNull { cuePriority(it) }
            if (!manualCueTracker.active()) aiVision.submitCue(working, strongestRaw)
            val localAi = if (!manualCueTracker.active()) aiVision.latestCue(1600L) else null
            if (!manualCueTracker.active() && localAi != null && localAi.confidence >= 0.70 && instantaneousCues.isNotEmpty()) {
                val idx = instantaneousCues.indices.maxByOrNull { cuePriority(instantaneousCues[it]) } ?: -1
                if (idx >= 0) instantaneousCues = instantaneousCues.toMutableList().also { list ->
                    list[idx] = list[idx].copy(label = localAi.label, confidence = max(list[idx].confidence, localAi.confidence))
                }
            }

            // Cloud is a rare semantic fallback, not the live tracker.
            val cueForCloud = instantaneousCues.maxByOrNull { cuePriority(it) }
            if (!manualCueTracker.active() && cueForCloud != null && (localAi == null || localAi.confidence < 0.72)) {
                cloudVision.submitIfAllowed(working, cueForCloud, localAi?.confidence ?: 0.0)
            }
            val cloud = cloudVision.latest(2200L)
            if (!manualCueTracker.active() && cloud != null && cloud.confidence >= 0.72 && instantaneousCues.isNotEmpty() && (localAi == null || localAi.confidence < 0.72)) {
                val idx = instantaneousCues.indices.maxByOrNull { cuePriority(instantaneousCues[it]) } ?: -1
                if (idx >= 0) instantaneousCues = instantaneousCues.toMutableList().also { list ->
                    list[idx] = list[idx].copy(label = cloud.label + " [CLOUD]", confidence = max(list[idx].confidence, cloud.confidence))
                }
            }

            // Persistent temporal tracking clusters repeated pieces of one flag/plant into stable cues.
            var motionCues = cueTracker.update(instantaneousCues, cameraTimestampNs)
            if (optical.mode == OpticalModeDetector.Mode.SPOTTING_SCOPE) {
                val cx = working.cols() / 2.0
                val cy = working.rows() / 2.0
                val r = min(working.cols(), working.rows()) * 0.60
                motionCues = motionCues.filter { q ->
                    val qx = (q.leftPx + q.rightPx) / 2.0
                    val qy = (q.topPx + q.bottomPx) / 2.0
                    hypot(qx - cx, qy - cy) <= r
                }
            }

            if (autoRoiEnabled && (autoRoi == null || weakSignalFrames >= 8) && frameIndex - lastAutoSearchFrame >= 10) {
                findBestAtmosphericRoi(working)?.let { candidate ->
                    if (autoRoi == null || rectDistance(autoRoi!!, candidate) > 20) {
                        autoRoi = candidate
                        opticalFlow.reset(); blockMatching.reset(); textureVariance.reset(); frequencyDomain.reset()
                    }
                }
                lastAutoSearchFrame = frameIndex
            }

            val roi = sanitizeRoi(
                if (autoRoiEnabled) autoRoi ?: defaultCentralRoi(working.cols(), working.rows())
                else roiSupplier(working.cols(), working.rows()),
                working.cols(), working.rows()
            )
            roiMat = Mat(working, CvRect(roi.left, roi.top, roi.width(), roi.height())).clone()
            roiFloat = Mat(); roiMat.convertTo(roiFloat, CvType.CV_32F)

            val ofResult = opticalFlow.process(roiMat)
            val bmResult = blockMatching.process(roiFloat)
            val tvResult = textureVariance.process(roiFloat)
            val fdResult = frequencyDomain.process(roiFloat, roiFloat.cols()/2)

            val movingObjectInRoi = motionCues.any { q ->
                val cx=(q.leftPx+q.rightPx)/2; val cy=(q.topPx+q.bottomPx)/2
                cx in roi.left..roi.right && cy in roi.top..roi.bottom && q.confidence >= 0.52
            }
            val daylightEnough = (ambientLux ?: 100f) >= 20f
            val scopeMode = optical.mode == OpticalModeDetector.Mode.SPOTTING_SCOPE

            // In scope mode the user may deliberately focus into the air mass rather than the target.
            // Therefore target softness / reduced spatial texture must NOT by itself reject mirage.
            val textureOk = if (scopeMode) tvResult.spatialTexture > 28.0 else tvResult.spatialTexture > 58.0
            val responseOk = if (scopeMode) bmResult.meanResponse >= 0.075 else bmResult.meanResponse >= 0.10
            val inlierOk = if (scopeMode) bmResult.inlierFraction >= 0.14 else bmResult.inlierFraction >= 0.18
            val rawMirageCandidate = daylightEnough && sceneLuma >= 38.0 &&
                stabInfo.trackingOk &&
                textureOk && tvResult.activityIndex in 0.055..8.0 &&
                inlierOk && responseOk &&
                bmResult.angularDispersion.isFinite() && bmResult.angularDispersion <= 0.76 &&
                bmResult.meanMagnitude <= 8.5 && !movingObjectInRoi

            mirageCandidateFrames = if (rawMirageCandidate) (mirageCandidateFrames + 1).coerceAtMost(12) else (mirageCandidateFrames - 1).coerceAtLeast(0)
            val sufficientSignal = mirageCandidateFrames >= 3
            weakSignalFrames = if (sufficientSignal || motionCues.isNotEmpty()) 0 else weakSignalFrames + 1

            val baseScore = computeSignalScore(tvResult.spatialTexture, tvResult.activityIndex, bmResult.inlierFraction, bmResult.angularDispersion)
            val signalScore = if (sufficientSignal) baseScore else baseScore * 0.35
            val mirageClockAngle = deriveClockAngleFromVectors(bmResult.vectors)
            val mirageClock = if (sufficientSignal && mirageClockAngle.isFinite()) MotionCueDetector.clockLabel(mirageClockAngle) else "--"
            val (mirageStable, mirageStableFor) = updateMirageStability(sufficientSignal, mirageClockAngle, bmResult.magnitude, cameraTimestampNs)

            val fusion = windFusion.update(
                mirageDetected = sufficientSignal,
                mirageStable = mirageStable,
                mirageClockAngleDeg = mirageClockAngle,
                mirageSignalScore = signalScore,
                cues = motionCues,
                nowNs = cameraTimestampNs
            )

            val primaryCue = motionCues.maxByOrNull { cuePriority(it) }
            val primaryCueLabel = primaryCue?.label?.replace(" [CLOUD]", "") ?: "NONE"
            val cueScore = primaryCue?.let { cuePriority(it) } ?: 0.0
            val learning = learningWindow.update(
                nowNs = cameraTimestampNs,
                cueScore = cueScore,
                mirageScore = if (sufficientSignal) signalScore else 0.0,
                agreement = fusion.directionAgreement,
                trackingOk = stabInfo.trackingOk || manualCueTracker.active()
            )

            val stage = when {
                manualCueTracker.active() && primaryCue != null -> "USER LOCK • ${manualCueTracker.activeLabel()} • TRACKING"
                manualCueTracker.active() && primaryCue == null -> "USER LOCK • ${manualCueTracker.activeLabel()} • WAITING FOR MOTION"
                optical.mode == OpticalModeDetector.Mode.DETECTING -> "SCANNING • DETECTING MODE"
                optical.changedRecently -> if (scopeMode) "SCANNING • REACQUIRING SCOPE" else "SCANNING • STABILIZING CAMERA"
                !stabInfo.trackingOk -> "SCANNING • STABILIZING"
                motionCues.isEmpty() && !sufficientSignal -> "SCANNING • LOOKING FOR READABLE CUES"
                primaryCue != null && learning.percent < 30 -> "DETECTED • $primaryCueLabel"
                primaryCue != null && learning.percent < 70 -> "ANALYSING • $primaryCueLabel"
                sufficientSignal && primaryCue == null && learning.percent < 70 -> "ANALYSING • MIRAGE"
                fusion.state == "INSUFFICIENT" -> "ANALYSING • BUILDING ESTIMATE"
                else -> "RESULT • WIND ESTIMATE READY"
            }

            val result = AnalysisResult(
                cameraTimestampNs, SystemClock.elapsedRealtimeNanos(), frameIndex++,
                working.cols(), working.rows(), roi.left, roi.top, roi.width(), roi.height(),
                stabInfo.stepTransform.dx, stabInfo.stepTransform.dy, stabInfo.stepTransform.rotationDeg, stabInfo.disturbance, stabInfo.trackingOk,
                ofResult.angleDeg, ofResult.magnitude, ofResult.meanMagnitude, ofResult.activeFraction, ofResult.vectors,
                bmResult.angleDeg, bmResult.magnitude, bmResult.meanMagnitude, bmResult.angularDispersion, bmResult.meanResponse, bmResult.inlierFraction, bmResult.vectors,
                tvResult.activityIndex, tvResult.spatialTexture,
                fdResult.dominantFreqHz, fdResult.spectralEnergy, fdResult.spectralCentroidHz,
                sufficientSignal, mirageClock, mirageStable, mirageStableFor, signalScore, sceneLuma,
                motionCues,
                fusion.state, fusion.clockDirection, fusion.confidence, fusion.sources,
                fusion.windMinMps, fusion.windMaxMps, fusion.stableForSec, fusion.directionAgreement,
                opticalMode = when (optical.mode) {
                    OpticalModeDetector.Mode.SPOTTING_SCOPE -> "SPOTTING SCOPE"
                    OpticalModeDetector.Mode.PHONE -> "PHONE CAMERA"
                    else -> "DETECTING"
                },
                opticalModeConfidence = optical.confidence,
                analysisStage = stage,
                learningPercent = learning.percent,
                learningUseful = learning.useful,
                trackedCueCount = motionCues.size,
                primaryCueLabel = primaryCueLabel,
                primaryCueConfidence = primaryCue?.confidence ?: 0.0,
                reasoningConfigured = reasoningScene.configured(),
                reasoningState = reasoning?.state ?: if (reasoningScene.configured()) "ANALYSING" else "OFFLINE",
                reasoningConfidence = reasoning?.confidence ?: 0.0,
                reasoningSummary = reasoning?.summary ?: "",
                reasoningDirection = reasoning?.direction ?: "UNKNOWN",
                reasoningSpeedBandMph = reasoning?.speedBandMph ?: "UNKNOWN",
                reasoningCueSummary = reasoning?.cues?.joinToString(" • ") { "${it.label}:${it.sensitivity}" } ?: "",
                reasoningRationale = reasoning?.rationale ?: ""
            )
            onResult(result, image)
        } catch (t: Throwable) {
            // A bad/ambiguous frame must never terminate the field app.
            // Log it and allow CameraX to feed the next frame.
            Log.e("MirageFrameAnalyzer", "Frame analysis failed; skipping frame", t)
            resetForNewRoi()
        } finally {
            try { fullGray?.release() } catch (_: Throwable) {}
            try { rotated?.release() } catch (_: Throwable) {}
            try { working?.release() } catch (_: Throwable) {}
            try { roiMat?.release() } catch (_: Throwable) {}
            try { roiFloat?.release() } catch (_: Throwable) {}
            try { stabilizedToRelease?.release() } catch (_: Throwable) {}
            try { image.close() } catch (_: Throwable) {}
        }
    }

    private fun cuePriority(q: MotionCue): Double {
        val s = q.label.uppercase()
        val semantic = when {
            "FLAG" in s || "FABRIC" in s || "DRAP" in s || "PENNANT" in s || "WINDSOCK" in s || "LAUNDRY" in s || "TOWEL" in s || "SAIL" in s -> 1.0
            "SMOKE" in s || "FOG" in s || "MIST" in s -> 0.95
            "FOLIAGE" in s || "LEAF" in s || "GRASS" in s || "REED" in s -> 0.88
            "PALM" in s || "BRANCH" in s || "TWIG" in s -> 0.76
            "ROPE" in s || "LINE" in s -> 0.68
            else -> 0.35
        }
        return semantic * q.confidence
    }

    /** Mask the corners outside the spotting-scope optical circle so external scene motion cannot vote. */
    private fun applyScopeMask(gray: Mat) {
        if (gray.empty()) return
        val mask = Mat.zeros(gray.rows(), gray.cols(), CvType.CV_8UC1)
        val radius = (min(gray.cols(), gray.rows()) * 0.60).toInt().coerceAtLeast(20)
        Imgproc.circle(mask, Point(gray.cols() / 2.0, gray.rows() / 2.0), radius, Scalar(255.0), -1)
        val masked = Mat()
        Core.bitwise_and(gray, gray, masked, mask)
        masked.copyTo(gray)
        mask.release(); masked.release()
    }

    private fun updateMirageStability(sufficient:Boolean, angle:Double, magnitude:Double, nowNs:Long):Pair<Boolean,Double>{
        if(!sufficient || !angle.isFinite() || !magnitude.isFinite()){ mirageStableSinceNs=nowNs; lastMirageClockAngle=angle; lastMirageMagnitude=magnitude; return false to 0.0 }
        if(mirageStableSinceNs==0L) mirageStableSinceNs=nowNs
        val angleOk=!lastMirageClockAngle.isFinite() || angularDistance(angle,lastMirageClockAngle)<=25.0
        val magOk=!lastMirageMagnitude.isFinite() || lastMirageMagnitude<0.02 || magnitude/lastMirageMagnitude.coerceAtLeast(0.02) in 0.60..1.65
        if(!angleOk || !magOk) mirageStableSinceNs=nowNs
        lastMirageClockAngle=angle; lastMirageMagnitude=magnitude
        val seconds=(nowNs-mirageStableSinceNs).coerceAtLeast(0L)/1_000_000_000.0
        return (seconds>=3.0) to seconds
    }

    private fun deriveClockAngleFromVectors(vectors:List<VectorArrow>):Double{
        val trusted=vectors.filter{it.response>=0.15f && hypot(it.dx.toDouble(),it.dy.toDouble())>0.02}; if(trusted.isEmpty()) return Double.NaN
        return MotionCueDetector.vectorToClockAngle(median(trusted.map{it.dx.toDouble()}),median(trusted.map{it.dy.toDouble()}))
    }

    private fun computeSignalScore(texture:Double,activity:Double,inliers:Double,dispersion:Double):Double{
        val t=(texture/250.0).coerceIn(0.0,1.0); val a=(activity/0.35).coerceIn(0.0,1.0); val i=inliers.coerceIn(0.0,1.0); val coherence=if(dispersion.isFinite())(1.0-dispersion).coerceIn(0.0,1.0)else 0.0
        return (0.18*t+0.38*a+0.22*i+0.22*coherence).coerceIn(0.0,1.0)
    }

    private fun findBestAtmosphericRoi(gray:Mat):Rect?{
        val ref=autoSearchReference
        if(ref==null || ref.size()!=gray.size()){autoSearchReference?.release();autoSearchReference=gray.clone();return null}
        val diff=Mat();Core.absdiff(gray,ref,diff); val cols=4; val rows=3; var bestScore=Double.NEGATIVE_INFINITY; var bestCx=gray.cols()/2; var bestCy=gray.rows()/2; val tileW=gray.cols()/cols; val tileH=gray.rows()/rows
        for(r in 0 until rows)for(c in 0 until cols){val x=c*tileW;val y=r*tileH;val w=if(c==cols-1)gray.cols()-x else tileW;val h=if(r==rows-1)gray.rows()-y else tileH;if(w<16||h<16)continue;val rect=CvRect(x,y,w,h);val tile=Mat(gray,rect);val d=Mat(diff,rect);val lap=Mat();Imgproc.Laplacian(tile,lap,CvType.CV_32F);val mean=MatOfDouble();val std=MatOfDouble();Core.meanStdDev(lap,mean,std);val texture=std.toArray().firstOrNull()?:0.0;val activity=Core.mean(d).`val`[0];val objectPenalty=if(activity>20.0)(activity-20.0)*2.2 else 0.0;val score=texture*0.45+activity*1.6-objectPenalty;if(score>bestScore){bestScore=score;bestCx=x+w/2;bestCy=y+h/2};lap.release();mean.release();std.release()}
        diff.release();autoSearchReference?.release();autoSearchReference=gray.clone();val roiW=(gray.cols()*0.48).toInt().coerceAtLeast(120).coerceAtMost(gray.cols());val roiH=(gray.rows()*0.48).toInt().coerceAtLeast(90).coerceAtMost(gray.rows());val left=(bestCx-roiW/2).coerceIn(0,max(0,gray.cols()-roiW));val top=(bestCy-roiH/2).coerceIn(0,max(0,gray.rows()-roiH));return Rect(left,top,left+roiW,top+roiH)
    }
    private fun defaultCentralRoi(w:Int,h:Int):Rect{val rw=(w*.55).toInt().coerceAtLeast(2);val rh=(h*.55).toInt().coerceAtLeast(2);val l=(w-rw)/2;val t=(h-rh)/2;return Rect(l,t,l+rw,t+rh)}
    private fun sanitizeRoi(r:Rect,w:Int,h:Int):Rect{val left=r.left.coerceIn(0,w-2);val top=r.top.coerceIn(0,h-2);val right=r.right.coerceIn(left+1,w);val bottom=r.bottom.coerceIn(top+1,h);return Rect(left,top,right,bottom)}
    private fun rectDistance(a:Rect,b:Rect)=abs(a.centerX()-b.centerX())+abs(a.centerY()-b.centerY())
    private fun median(values:List<Double>):Double{val s=values.sorted();val m=s.size/2;return if(s.size%2==0)(s[m-1]+s[m])/2.0 else s[m]}
    private fun angularDistance(a:Double,b:Double):Double{val d=abs(a-b)%360.0;return min(d,360.0-d)}
    private fun imageProxyToGrayMat(image:ImageProxy):Mat{val yPlane=image.planes[0];val rowStride=yPlane.rowStride;val width=image.width;val height=image.height;val buffer=yPlane.buffer;val mat=Mat(height,width,CvType.CV_8UC1);if(rowStride==width){val bytes=ByteArray(buffer.remaining());buffer.get(bytes);mat.put(0,0,bytes)}else{val rowBytes=ByteArray(rowStride);for(row in 0 until height){buffer.position(row*rowStride);buffer.get(rowBytes,0,rowStride);mat.put(row,0,rowBytes.copyOf(width))}};return mat}
    private fun rotateToUpright(mat:Mat,rotationDegrees:Int):Mat{val out=Mat();when(rotationDegrees){90->Core.rotate(mat,out,Core.ROTATE_90_CLOCKWISE);180->Core.rotate(mat,out,Core.ROTATE_180);270->Core.rotate(mat,out,Core.ROTATE_90_COUNTERCLOCKWISE);else->return mat.clone()};return out}
}
