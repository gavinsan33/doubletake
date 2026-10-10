plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.doubletake.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.doubletake.app"
        minSdk = 29 // AudioPlaybackCapture (planned) and KEY_PREPEND_HEADER_TO_SYNC_FRAMES
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Built by `make android-aar` (gomobile bind of ./mobile).
    implementation(files("libs/doubletake.aar"))
}
