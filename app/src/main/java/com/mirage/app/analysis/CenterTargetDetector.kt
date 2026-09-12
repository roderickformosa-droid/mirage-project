package com.mirage.app.analysis

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Conservative, model-free centre target helper for the field-test build.
 * It intentionally avoids claiming semantic AI recognition that is not bundled in the APK.
 * It can identify a strong rectangular target, foliage/tree-like texture, or leave it unclassified.
 */
class CenterTargetDetector {
    data class Candidate(val label: String, val confidence: Double, val rect: Rect)

    fun detect(gray: Mat): Candidate? {
        if (gray.empty()) return null
        val cx = gray.cols() / 2; val cy = gray.rows() / 2
        val searchW = (gray.cols() * 0.55).toInt(); val searchH = (gray.rows() * 0.55).toInt()
        val sx = (cx - searchW / 2).coerceAtLeast(0); val sy = (cy - searchH / 2).coerceAtLeast(0)
        val roiRect = Rect(sx, sy, searchW.coerceAtMost(gray.cols()-sx), searchH.coerceAtMost(gray.rows()-sy))
        val roi = Mat(gray, roiRect)
        val blur = Mat(); val edges = Mat(); Imgproc.GaussianBlur(roi, blur, Size(5.0,5.0),0.0)
        Imgproc.Canny(blur, edges, 55.0, 145.0)
        val contours = mutableListOf<MatOfPoint>(); val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val localCx = searchW/2; val localCy = searchH/2
        val candidates = contours.mapNotNull { c ->
            val r = Imgproc.boundingRect(c); val area = r.area()
            if (area < searchW*searchH*0.008 || area > searchW*searchH*0.75 || r.width < 18 || r.height < 18) null
            else {
                val dx = abs((r.x+r.width/2)-localCx); val dy = abs((r.y+r.height/2)-localCy)
                if (dx > searchW*0.22 || dy > searchH*0.22) null else r
            }
        }.sortedByDescending { it.area() }
        val r = candidates.firstOrNull()
        val out = if (r != null) {
            val patch = Mat(roi, r); val mean=MatOfDouble(); val std=MatOfDouble(); Core.meanStdDev(patch,mean,std)
            val texture = std.toArray().firstOrNull() ?: 0.0
            val aspect = r.width.toDouble()/r.height.coerceAtLeast(1)
            val rectangular = aspect in 0.35..2.8
            val label = when {
                rectangular && texture < 48.0 -> "DOOR/SIGN-LIKE TARGET"
                texture > 58.0 -> "TREE/FOLIAGE-LIKE TARGET"
                else -> "UNCLASSIFIED TARGET"
            }
            val conf = when(label) {
                "DOOR/SIGN-LIKE TARGET" -> 0.58
                "TREE/FOLIAGE-LIKE TARGET" -> 0.52
                else -> 0.35
            }
            Candidate(label, conf, Rect(r.x+sx,r.y+sy,r.width,r.height))
        } else null
        contours.forEach { it.release() }; hierarchy.release(); blur.release(); edges.release()
        return out
    }
}
