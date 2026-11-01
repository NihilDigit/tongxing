import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val embedLocalServiceConfig = providers.gradleProperty("embedLocalServiceConfig")
    .map(String::toBooleanStrict).getOrElse(false)
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (embedLocalServiceConfig && localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use(localProperties::load)
}
val serviceConfigFields = mapOf(
    "MCP_AMAP_URL" to "mcp.amap.url",
    "MCP_12306_URL" to "mcp.12306.url",
    "MCP_VARIFLIGHT_URL" to "mcp.variflight.url",
    "MCP_DIDI_SANDBOX_URL" to "mcp.didi.sandbox.url",
    "CLOUD_API_KEY" to "cloudApiKey"
)

android {
    namespace = "com.nihildigit.tongxing"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.nihildigit.tongxing"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        serviceConfigFields.keys.forEach { buildConfigField("String", it, "\"\"") }
    }

    buildTypes {
        debug {
            if (embedLocalServiceConfig) {
                serviceConfigFields.forEach { (field, property) ->
                    buildConfigField("String", field,
                        groovy.json.JsonOutput.toJson(localProperties.getProperty(property, "")))
                }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true  // 启用 BuildConfig 生成
    }

    packaging {
        jniLibs {
            // 与 safetycore 对齐，保持 QNN/NPU 库原样放入 apk（不压缩/分包）
            useLegacyPackaging = true
        }
    }

    // 确保 .tflite/.dlc/.bin 不压缩，便于 AssetFileDescriptor.openFd 直接 mmap
    androidResources {
        noCompress += listOf("tflite", "dlc", "bin", "onnx")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    // Core modules
    implementation(project(":lightway_safetycore:safetycore-core"))
    implementation(project(":lightway_agentcore:android:agentcore"))
    implementation(project(":lightway_llama:llm-sdk"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // Foreground service as LifecycleOwner (OverlayService)
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    // ViewTreeSavedStateRegistryOwner (optional but harmless for overlays)
    implementation("androidx.savedstate:savedstate:1.3.1")

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    
    // DocumentFile for SAF folder picker
    implementation(libs.androidx.documentfile)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    // JSON 序列化
    implementation(libs.kotlinx.serialization.json)

    // Tuning server (embedded HTTP/WebSocket)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // WorkManager for background model download
    implementation(libs.androidx.work.runtime.ktx)

    // Sherpa-ONNX 离线语音识别 (替换 Vosk) - 使用官方 AAR
    implementation(files("libs/sherpa-onnx-1.12.18.aar"))

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(project(":lightway_safetycore:qnn-sdk"))
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
