import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing details live outside the repo (see .gitignore). When the
// file is absent — anyone who just cloned this — the release build falls back
// to the debug key below, so the project still compiles without secrets.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.gesturemouse"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gesturemouse"
        minSdk = 28              // BluetoothHidDevice was added in Android 9
        targetSdk = 34
        // Bump versionCode for every build you hand to someone — Android
        // refuses to install an APK whose versionCode is lower than what's
        // already on the device.
        versionCode = 7
        versionName = "1.6.0"

        // phones are ARM. shipping the x86 MediaPipe libs adds ~20 MB that no
        // real handset will ever load.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 is left off deliberately. Almost all of the 45 MB is the
            // MediaPipe native libraries and the hand model, neither of which
            // shrinking touches, and MediaPipe resolves plenty by reflection —
            // so enabling it buys little and risks a runtime failure that only
            // shows up on someone else's phone.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // no keystore on this machine: still produce an installable APK
                signingConfigs.getByName("debug")
            }
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
        // version name/code shown in Settings and filled into support emails
        buildConfig = true
    }
    // the .task model is already compressed; letting aapt deflate it again
    // makes MediaPipe's memory-mapped load fail at runtime
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // camera for the air-gesture tab
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // on-device hand tracking
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    testImplementation("junit:junit:4.13.2")
}
