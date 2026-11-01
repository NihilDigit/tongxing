// 使用根项目已加载的插件，避免重复声明版本
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.serialization)
}

configurations.all {
    resolutionStrategy {
        // 强制使用统一的 OkHttp 版本，避免 Ktor 引入不同版本导致冲突
        force("com.squareup.okhttp3:okhttp:4.12.0")
    }
}

android {
    namespace = "com.nihildigit.agentcore"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
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

    testOptions {
        unitTests {
            // Return default values for unmocked Android framework methods (e.g., android.util.Log)
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    
    // Kotlin
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.json5)

    // Networking / streaming
    implementation(libs.okhttp)
    implementation(libs.rxjava2)
    implementation(libs.rxandroid2)

    // Ktor client for MCP SSE/WebSocket
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.sse)

    // External services
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.dashscope.sdk.java)

    // Local LLM (llama.cpp)
    implementation(project(":lightway_llama:llm-sdk"))

    // Testing
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
}
