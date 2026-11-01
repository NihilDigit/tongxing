plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nihildigit.qnninferencesdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        // QNN supports arm64 only
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES")
        }
    }
    androidResources {
        noCompress += "tflite"
        noCompress += "dlc"
        noCompress += "bin"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // TensorFlow Lite and GPU delegate
    api("org.tensorflow:tensorflow-lite:2.16.1")
    api("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    api("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
    api("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")

    // Qualcomm QNN runtime and LiteRT delegate
    api("com.qualcomm.qti:qnn-runtime:2.40.0")
    api("com.qualcomm.qti:qnn-litert-delegate:2.40.0")

    // OpenCV for fast image preprocessing
    implementation("org.opencv:opencv:4.12.0")
}
