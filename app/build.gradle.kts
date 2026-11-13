plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.musiclightsynk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.musiclightsynk"
        // Fire OS 6 (API 25) and newer: every Fire TV Stick 4K / 4K Max, Fire TV Cube, Android TV boxes.
        minSdk = 25
        targetSdk = 34
        versionCode = 8
        versionName = "0.8.0"
    }

    signingConfigs {
        // A fixed key committed to the repo so every CI build can be installed over the previous
        // one with `adb install -r`. Fine for a personal sideloaded app; don't reuse it elsewhere.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only ships stubs).
    testImplementation("org.json:json:20240303")
}
