import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.exists()) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.gijiroku.benchmark"
    // NOTE: adjust to whichever NDK version is installed via Android Studio's SDK Manager.
    ndkVersion = "26.1.10909125"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gijiroku.benchmark"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // GGML_NATIVE=OFF: cross-compiling for Android, must not probe the *build host's* CPU features.
                arguments += listOf("-DGGML_NATIVE=OFF", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags += listOf("-std=c++17")
            }
        }
        // large-v3 targets arm64 flagship SoCs only; skip 32-bit to keep the build light.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (releaseKeystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseKeystoreProperties.getProperty("storeFile")))
                storePassword = requireNotNull(releaseKeystoreProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseKeystoreProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseKeystoreProperties.getProperty("keyPassword"))
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Debug native builds are dramatically slower for whisper.cpp; always benchmark Release.
            isJniDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17

    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":llama-runtime"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.biometric:biometric:1.1.0")
    // Portable backups derive their wrapping key locally with Argon2id.
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.6.0")
    // Offline-only QR rendering for the one-time recovery key display.
    implementation("com.google.zxing:core:3.5.4")
    // Speaker-embedding inference (phase3 diarization) — official Microsoft AAR, no NDK build needed.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json classes are stubs in local JVM tests; provide the compatible runtime there.
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
