package com.mirage.app.analysis

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import org.opencv.core.*
import org.opencv.core.Rect as CvRect
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** v0.61 live visual-wind pipeline with conservative mirage gating + on-device semantic labels. */
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
    private val centerTargetDetector = CenterTargetDetector()
    private val aiVision = AiVisionClassifier()
    private val cloudVision = CloudVisionClassifier(context)

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
    private fun intervalForHz(hz: Double): Long = (1_000_000_000.0 / hz).toLong()

    fun resetForNewRoi() {
        opticalFlow.reset(); blockMatching.reset(); textureVariance.reset(); frequencyDomain.reset()
        mirageStableSinceNs = 0L; lastMirageClockAngle = Double.NaN; lastMirageMagnitude = Double.NaN
        mirageCandidateFrames = 0
    }

    override fun analyze(image: ImageProxy) {
        val cameraTimestampNs = image.imageInfo.timestamp
        if (cameraTimestampNs - lastAnalyzedNs < minIntervalNs) { image.close(); return }
        lastAnalyzedNs = cameraTimestampNs

        var fullGray: Mat? = null; var rotated: Mat? = null; var roiMat: Mat? = null; var roiFloat: Mat? = null
        try {
            fullGray = imageProxyToGrayMat(image)
            rotated = rotateToUpright(fullGray, image.imageInfo.rotationDegrees)
            val (stabilizedU8, stabInfo) = stabilizer.process(rotated)
            val sceneLuma = Core.mean(stabilizedU8).`val`[0]

            var motionCues = if (!stabInfo.disturbance && stabInfo.trackingOk) motionCueDetector.process(stabilizedU8, cameraTimestampNs) else emptyList()
            val strongestCue = motionCues.maxByOrNull { it.confidence }
            aiVision.submitTarget(stabilizedU8)
            aiVision.submitCue(stabilizedU8, strongestCue)
            aiVision.latestCue()?.let { ai ->
                if (motionCues.isNotEmpty()) {
                    val idx = motionCues.indices.maxByOrNull { motionCues[it].confidence } ?: -1
                    if (idx >= 0) motionCues = motionCues.toMutableList().also { it[idx] = it[idx].copy(label = ai.label, confidence = max(it[idx].confidence, ai.confidence)) }
                }
            }

            // Cloud Vision is deliberately a rare fallback: only uncertain moving cues are sent.
            // It never receives continuous video and its result never overrides strong local recognition.
            val cueForCloud = motionCues.maxByOrNull { it.confidence }
            val localAi = aiVision.latestCue()
            if (cueForCloud != null && (localAi == null || localAi.confidence < 0.72)) {
                cloudVision.submitIfAllowed(stabilizedU8, cueForCloud, localAi?.confidence ?: 0.0)
            }
            cloudVision.latest()?.let { cloud ->
                if (motionCues.isNotEmpty() && (localAi == null || localAi.confidence < 0.72)) {
                    val idx = motionCues.indices.maxByOrNull { motionCues[it].confidence } ?: -1
                    if (idx >= 0) motionCues = motionCues.toMutableList().also { list ->
                        list[idx] = list[idx].copy(
                            label = cloud.label + " [CLOUD]",
                            confidence = max(list[idx].confidence, cloud.confidence)
                        )
                    }
                }
            }

            if (autoRoiEnabled && (autoRoi == null || weakSignalFrames >= 8) && frameIndex - lastAutoSearchFrame >= 10) {
                findBestAtmosphericRoi(stabilizedU8)?.let { candidate ->
                    if (autoRoi == null || rectDistance(autoRoi!!, candidate) > 20) { autoRoi = candidate; resetForNewRoi() }
                }
                lastAutoSearchFrame = frameIndex
            }

            val roi = sanitizeRoi(if (autoRoiEnabled) autoRoi ?: defaultCentralRoi(stabilizedU8.cols(), stabilizedU8.rows()) else roiSupplier(stabilizedU8.cols(), stabilizedU8.rows()), stabilizedU8.cols(), stabilizedU8.rows())
            roiMat = Mat(stabilizedU8, CvRect(roi.left, roi.top, roi.width(), roi.height())).clone()
            roiFloat = Mat(); roiMat.convertTo(roiFloat, CvType.CV_32F)

            val ofResult = opticalFlow.process(roiMat)
            val bmResult = blockMatching.process(roiFloat)
            val tvResult = textureVariance.process(roiFloat)
            val fdResult = frequencyDomain.process(roiFloat, roiFloat.cols()/2)

            // Mirage must be subtle, coherent refractive shimmer, not simply "something changed".
            // Low-light sensor noise and moving solid objects are aggressively rejected.
            val movingObjectInRoi = motionCues.any { q ->
                val cx=(q.leftPx+q.rightPx)/2; val cy=(q.topPx+q.bottomPx)/2
                cx in roi.left..roi.right && cy in roi.top..roi.bottom && q.confidence >= 0.40
            }
            val daylightEnough = (ambientLux ?: 100f) >= 25f
            val rawMirageCandidate = daylightEnough && sceneLuma >= 48.0 &&
                !stabInfo.disturbance && stabInfo.trackingOk &&
                tvResult.spatialTexture > 65.0 && tvResult.activityIndex in 0.08..8.0 &&
                bmResult.inlierFraction >= 0.18 && bmResult.meanResponse >= 0.10 &&
                bmResult.angularDispersion.isFinite() && bmResult.angularDispersion <= 0.72 &&
                bmResult.meanMagnitude <= 8.0 && !movingObjectInRoi

            mirageCandidateFrames = if (rawMirageCandidate) (mirageCandidateFrames + 1).coerceAtMost(10) else 0
            val sufficientSignal = mirageCandidateFrames >= 3
            weakSignalFrames = if (sufficientSignal) 0 else weakSignalFrames + 1

            val baseScore = computeSignalScore(tvResult.spatialTexture, tvResult.activityIndex, bmResult.inlierFraction, bmResult.angularDispersion)
            val signalScore = if (sufficientSignal) baseScore else baseScore * 0.35
            val mirageClockAngle = deriveClockAngleFromVectors(bmResult.vectors)
            val mirageClock = if (sufficientSignal && mirageClockAngle.isFinite()) MotionCueDetector.clockLabel(mirageClockAngle) else "--"
            val (mirageStable, mirageStableFor) = updateMirageStability(sufficientSignal, mirageClockAngle, bmResult.magnitude, cameraTimestampNs)

            val heuristicTarget = if (!stabInfo.disturbance) centerTargetDetector.detect(stabilizedU8) else null
            val aiTarget = aiVision.latestTarget()
            val targetLabel = aiTarget?.label ?: heuristicTarget?.label ?: ""
            val targetConfidence = aiTarget?.confidence ?: heuristicTarget?.confidence ?: 0.0
            val cx = stabilizedU8.cols()/2; val cy = stabilizedU8.rows()/2
            val tw=(stabilizedU8.cols()*0.16).toInt(); val th=(stabilizedU8.rows()*0.16).toInt()

            val result = AnalysisResult(
                cameraTimestampNs, SystemClock.elapsedRealtimeNanos(), frameIndex++,
                stabilizedU8.cols(), stabilizedU8.rows(), roi.left, roi.top, roi.width(), roi.height(),
                stabInfo.stepTransform.dx, stabInfo.stepTransform.dy, stabInfo.stepTransform.rotationDeg, stabInfo.disturbance, stabInfo.trackingOk,
                ofResult.angleDeg, ofResult.magnitude, ofResult.meanMagnitude, ofResult.activeFraction, ofResult.vectors,
                bmResult.angleDeg, bmResult.magnitude, bmResult.meanMagnitude, bmResult.angularDispersion, bmResult.meanResponse, bmResult.inlierFraction, bmResult.vectors,
                tvResult.activityIndex, tvResult.spatialTexture,
                fdResult.dominantFreqHz, fdResult.spectralEnergy, fdResult.spectralCentroidHz,
                sufficientSignal, mirageClock, mirageStable, mirageStableFor, signalScore, sceneLuma,
                motionCues,
                targetLabel, targetConfidence,
                (cx-tw/2).coerceAtLeast(0), (cy-th/2).coerceAtLeast(0),
                (cx+tw/2).coerceAtMost(stabilizedU8.cols()), (cy+th/2).coerceAtMost(stabilizedU8.rows())
            )
            onResult(result,image)
            stabilizedU8.release()
        } finally {
            fullGray?.release(); rotated?.release(); roiMat?.release(); roiFloat?.release(); image.close()
        }
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
        return (0.25*t+0.35*a+0.20*i+0.20*coherence).coerceIn(0.0,1.0)
    }
    private fun findBestAtmosphericRoi(gray:Mat):Rect?{
        val ref=autoSearchReference
        if(ref==null || ref.size()!=gray.size()){autoSearchReference?.release();autoSearchReference=gray.clone();return null}
        val diff=Mat();Core.absdiff(gray,ref,diff); val cols=4; val rows=3; var bestScore=Double.NEGATIVE_INFINITY; var bestCx=gray.cols()/2; var bestCy=gray.rows()/2; val tileW=gray.cols()/cols; val tileH=gray.rows()/rows
        for(r in 0 until rows)for(c in 0 until cols){val x=c*tileW;val y=r*tileH;val w=if(c==cols-1)gray.cols()-x else tileW;val h=if(r==rows-1)gray.rows()-y else tileH;if(w<16||h<16)continue;val rect=CvRect(x,y,w,h);val tile=Mat(gray,rect);val d=Mat(diff,rect);val lap=Mat();Imgproc.Laplacian(tile,lap,CvType.CV_32F);val mean=MatOfDouble();val std=MatOfDouble();Core.meanStdDev(lap,mean,std);val texture=std.toArray().firstOrNull()?:0.0;val activity=Core.mean(d).`val`[0];val objectPenalty=if(activity>20.0)(activity-20.0)*2.2 else 0.0;val score=texture*0.55+activity*1.4-objectPenalty;if(score>bestScore){bestScore=score;bestCx=x+w/2;bestCy=y+h/2};lap.release();mean.release();std.release()}
        diff.release();autoSearchReference?.release();autoSearchReference=gray.clone();val roiW=(gray.cols()*0.48).toInt().coerceAtLeast(120);val roiH=(gray.rows()*0.48).toInt().coerceAtLeast(90);val left=(bestCx-roiW/2).coerceIn(0,gray.cols()-roiW);val top=(bestCy-roiH/2).coerceIn(0,gray.rows()-roiH);return Rect(left,top,left+roiW,top+roiH)
    }
    private fun defaultCentralRoi(w:Int,h:Int):Rect{val rw=(w*.55).toInt();val rh=(h*.55).toInt();val l=(w-rw)/2;val t=(h-rh)/2;return Rect(l,t,l+rw,t+rh)}
    private fun sanitizeRoi(r:Rect,w:Int,h:Int):Rect{val left=r.left.coerceIn(0,w-2);val top=r.top.coerceIn(0,h-2);val right=r.right.coerceIn(left+1,w);val bottom=r.bottom.coerceIn(top+1,h);return Rect(left,top,right,bottom)}
    private fun rectDistance(a:Rect,b:Rect)=abs(a.centerX()-b.centerX())+abs(a.centerY()-b.centerY())
    private fun median(values:List<Double>):Double{val s=values.sorted();val m=s.size/2;return if(s.size%2==0)(s[m-1]+s[m])/2.0 else s[m]}
    private fun angularDistance(a:Double,b:Double):Double{val d=abs(a-b)%360.0;return min(d,360.0-d)}
    private fun imageProxyToGrayMat(image:ImageProxy):Mat{val yPlane=image.planes[0];val rowStride=yPlane.rowStride;val width=image.width;val height=image.height;val buffer=yPlane.buffer;val mat=Mat(height,width,CvType.CV_8UC1);if(rowStride==width){val bytes=ByteArray(buffer.remaining());buffer.get(bytes);mat.put(0,0,bytes)}else{val rowBytes=ByteArray(rowStride);for(row in 0 until height){buffer.position(row*rowStride);buffer.get(rowBytes,0,rowStride);mat.put(row,0,rowBytes.copyOf(width))}};return mat}
    private fun rotateToUpright(mat:Mat,rotationDegrees:Int):Mat{val out=Mat();when(rotationDegrees){90->Core.rotate(mat,out,Core.ROTATE_90_CLOCKWISE);180->Core.rotate(mat,out,Core.ROTATE_180);270->Core.rotate(mat,out,Core.ROTATE_90_COUNTERCLOCKWISE);else->return mat.clone()};return out}
}
