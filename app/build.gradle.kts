plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mirage.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mirage.app"
        minSdk = 26   // Camera2 manual focus/exposure controls need API 23+; 26 keeps things simpler
        targetSdk = 34
        versionCode = 13
        versionName = "0.67-cue-capture-fix"

        // Optional Google Cloud Vision key. Prefer setting CLOUD_VISION_API_KEY as a
        // GitHub Actions secret/environment variable; blank keeps cloud recognition disabled.
        val cloudVisionKey = System.getenv("CLOUD_VISION_API_KEY") ?: ""
        buildConfigField("String", "CLOUD_VISION_API_KEY", "\"$cloudVisionKey\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Not used for this field-test build; we're shipping the debug APK only.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // --- CameraX ---
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion")

    // --- OpenCV for Android (official Maven artifact) ---
    // NOTE: if this artifact ever fails to resolve for you, the fallback is
    // downloading the OpenCV Android SDK .zip from opencv.org and importing
    // it as a local module -- see README_BUILD.md "If OpenCV via Maven fails".
    implementation("org.opencv:opencv:4.9.0")

    // On-device semantic labels for environmental wind cues only.
    implementation("com.google.mlkit:image-labeling:17.0.9")

    // --- AndroidX / Kotlin basics ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    testImplementation("junit:junit:4.13.2")
}
