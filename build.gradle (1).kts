plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.inaib.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.inaib.browser"
        minSdk = 21          // Android 5.0+: covers 1-2 GB RAM phones
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Shrinks code + resources: the APK stays around 1 MB because
            // InaiB has no third-party libraries (the engine is the system WebView).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        // Keeps the first release build from stopping on lint warnings; tighten later.
        checkReleaseBuilds = false
        abortOnError = false
    }
}
