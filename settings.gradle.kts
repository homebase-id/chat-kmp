rootProject.name = "chat-kmp"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Kotlin/Wasm's npm-aggregation tasks resolve cross-project configurations that are not safe
// under Gradle's parallel project execution — Gradle 9 turns this into a hard error
// ("configuration ':<module>:wasmJsTestNpmAggregated' was attempted without an exclusive lock").
// `org.gradle.parallel=true` is kept for the (much larger) Android/Desktop builds, but Android
// Studio injects `--parallel` from that property and it overrides run-config `--no-parallel`.
// So: disable parallel execution only for invocations that target a wasmJs task. Everything else
// keeps parallelism.
if (gradle.startParameter.taskNames.any { it.contains("wasm", ignoreCase = true) }) {
    gradle.startParameter.isParallelProjectExecutionEnabled = false
}

pluginManagement {
    repositories {
        mavenCentral()

        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }

        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("gradle/local-repo")
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// KMP Modules
include(":homebase-common")
include(":homebase-core")
include(":homebase-auth")
include(":homebase-chat")
include(":homebase-api")
include(":homebase-notifshared")
include(":image-editor-core")
include(":image-editor-ui")

// Apps
include(":desktopApp")
include(":androidApp")
include(":webApp")

