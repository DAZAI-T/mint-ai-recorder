plugins {
    id("com.android.library")
}

android {
    namespace = "com.gijiroku.llamaruntime"
    ndkVersion = "26.1.10909125"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                arguments += listOf("-DGGML_NATIVE=OFF", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags += listOf("-std=c++17")
            }
        }
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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isJniDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
