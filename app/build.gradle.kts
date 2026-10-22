plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pranav.morninggate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pranav.morninggate"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Still fully offline at runtime: CameraX reads frames locally and ZXing
    // decodes them on-device. No INTERNET permission, no network calls.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.activity:activity:1.9.3")
    implementation("com.google.zxing:core:3.5.3")
}
