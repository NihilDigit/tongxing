pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            // Limit google repo to Android/Google artifacts to avoid TLS issues for non-Google deps
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "tongxing"
include(":app")

// SafetyCore modules
include(":lightway_safetycore:safetycore-core")
include(":lightway_safetycore:qnn-sdk")

// AgentCore modules
include(":lightway_agentcore:android:agentcore")

// llama.cpp Android SDK (vendored)
include(":lightway_llama:llm-sdk")
project(":lightway_llama:llm-sdk").projectDir = file("third_party/llama.cpp/examples/llama.android/lib")
 
