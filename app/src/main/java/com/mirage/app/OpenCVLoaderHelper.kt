package com.mirage.app

import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * The org.opencv:opencv Maven artifact (4.9+) bundles native libraries
 * directly in the app, so this is a synchronous local init -- no separate
 * "OpenCV Manager" app needs to be installed on the phone, unlike older
 * OpenCV-for-Android setups you may have read about online.
 */
object OpenCVLoaderHelper {
    fun init(): Boolean {
        val ok = OpenCVLoader.initLocal()
        Log.i("OpenCVLoaderHelper", if (ok) "OpenCV loaded OK" else "OpenCV FAILED to load")
        return ok
    }
}
