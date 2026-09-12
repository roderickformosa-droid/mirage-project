package com.mirage.app.analysis

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * Direct port of Stage 1's stabilize.py: ORB features + partial-affine
 * (translation + rotation + uniform scale) registration of each frame onto
 * a fixed reference frame (frame 0). Same math, same disturbance-threshold
 * logic, same "estimate the frame-to-PREVIOUS-frame step, then compose it
 * into a cumulative reference-frame transform" approach.
 *
 * KNOWN DIFFERENCE FROM DESKTOP: Python's cv2.estimateAffinePartial2D call
 * explicitly sets ransacReprojThreshold=3.0. The Android/Java binding used
 * here calls the simpler 2-argument overload (OpenCV's default RANSAC
 * settings), because the full 7-argument overload's Java signature proved
 * awkward to get exactly right without a compiler to verify against. If
 * field-test cross-checks show a meaningful difference in disturbance
 * flagging between phone and desktop, switch to the explicit-threshold
 * overload here -- see README_BUILD.md "Known desktop/Android differences".
 */
class Stabilizer(
    maxFeatures: Int = 500,
    private val disturbanceShiftPx: Double = 8.0,
    private val disturbanceRotationDeg: Double = 1.5
) {
    data class StepTransform(val dx: Double, val dy: Double, val rotationDeg: Double, val scale: Double)
    data class StabResult(
        val stepTransform: StepTransform,
        val cumulativeTransform: StepTransform,
        val disturbance: Boolean,
        val trackingOk: Boolean
    )

    private val orb = ORB.create(maxFeatures)
    private val matcher = BFMatcher.create(org.opencv.core.Core.NORM_HAMMING, true)

    private var referenceGray: Mat? = null
    private var prevGray: Mat? = null
    private var prevKp: MatOfKeyPoint? = null
    private var prevDesc: Mat? = null

    // 2x3 cumulative transform mapping current frame -> reference frame.
    private var cumulativeM: Mat = Mat.eye(2, 3, org.opencv.core.CvType.CV_64F)

    /** Call once per new frame (already grayscale). Returns the stabilized Mat (caller owns it) + info. */
    fun process(graySrc: Mat): Pair<Mat, StabResult> {
        if (referenceGray == null) {
            referenceGray = graySrc.clone()
            prevGray = graySrc.clone()
            val kp = MatOfKeyPoint()
            val desc = Mat()
            orb.detectAndCompute(graySrc, Mat(), kp, desc)
            prevKp = kp
            prevDesc = desc
            cumulativeM = Mat.eye(2, 3, org.opencv.core.CvType.CV_64F)
            val identity = StepTransform(0.0, 0.0, 0.0, 1.0)
            return Pair(graySrc.clone(), StabResult(identity, identity, false, true))
        }

        val kpB = MatOfKeyPoint()
        val descB = Mat()
        orb.detectAndCompute(graySrc, Mat(), kpB, descB)

        var stepM: Mat? = null
        var trackingOk = false

        val pKp = prevKp
        val pDesc = prevDesc
        if (pDesc != null && !pDesc.empty() && !descB.empty() &&
            pKp!!.toArray().size >= 6 && kpB.toArray().size >= 6
        ) {
            val matches = MatOfDMatch()
            matcher.match(pDesc, descB, matches)
            val matchList = matches.toArray().sortedBy { it.distance }.take(200)
            if (matchList.size >= 6) {
                val prevPts = MatOfPoint2f(*matchList.map { pKp.toArray()[it.queryIdx].pt }.toTypedArray())
                val curPts = MatOfPoint2f(*matchList.map { kpB.toArray()[it.trainIdx].pt }.toTypedArray())
                // NOTE: (curPts -> prevPts) matches Python's estimateAffinePartial2D(pts_b, pts_a, ...)
                // i.e. we solve for the transform that maps the CURRENT frame onto the PREVIOUS frame.
                val m = Calib3d.estimateAffinePartial2D(curPts, prevPts)
                if (!m.empty()) {
                    stepM = m
                    trackingOk = true
                }
            }
        }

        val step: StepTransform
        if (trackingOk && stepM != null) {
            step = decompose(stepM)
            // cumulative_new = step ∘ cumulative_old  (both map -> reference space)
            cumulativeM = composeAffine(stepM, cumulativeM)
        } else {
            step = StepTransform(0.0, 0.0, 0.0, 1.0)
        }

        val cumulative = decompose(cumulativeM)
        val disturbance = abs(step.dx) > disturbanceShiftPx ||
            abs(step.dy) > disturbanceShiftPx ||
            abs(step.rotationDeg) > disturbanceRotationDeg ||
            !trackingOk

        val stabilized = Mat()
        org.opencv.imgproc.Imgproc.warpAffine(
            graySrc, stabilized, cumulativeM, graySrc.size(),
            org.opencv.imgproc.Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REPLICATE
        )

        prevGray = graySrc.clone()
        prevKp = kpB
        prevDesc = descB

        return Pair(stabilized, StabResult(step, cumulative, disturbance, trackingOk))
    }

    private fun decompose(m: Mat): StepTransform {
        val a = m.get(0, 0)[0]; val b = m.get(0, 1)[0]; val tx = m.get(0, 2)[0]
        val c = m.get(1, 0)[0]
        val scale = sqrt(a * a + c * c)
        val rotationDeg = Math.toDegrees(atan2(c, a))
        return StepTransform(tx, m.get(1, 2)[0], rotationDeg, scale)
    }

    /** Returns step ∘ base as a new 2x3 affine Mat (both treated as augmented 3x3 with [0,0,1] row). */
    private fun composeAffine(step: Mat, base: Mat): Mat {
        fun to3x3(m: Mat): Mat {
            val full = Mat.eye(3, 3, org.opencv.core.CvType.CV_64F)
            for (r in 0..1) for (cIdx in 0..2) full.put(r, cIdx, m.get(r, cIdx)[0])
            return full
        }
        val s3 = to3x3(step)
        val b3 = to3x3(base)
        val result3 = Mat()
        org.opencv.core.Core.gemm(s3, b3, 1.0, Mat(), 0.0, result3)
        val out = Mat(2, 3, org.opencv.core.CvType.CV_64F)
        for (r in 0..1) for (cIdx in 0..2) out.put(r, cIdx, result3.get(r, cIdx)[0])
        return out
    }
}
