import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "id.homebase.upload"
        compileSdk = libs.versions.android.targetSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    // iOS targets are declared for klib compilation only — NO framework binary.
    // homebase-upload is an `implementation` dependency of homebase-chat/homebase-core
    // (not Swift-facing), so its klib links transitively into their frameworks. Keeping
    // it off the Swift surface avoids framework-baseName churn (see plan risk #3).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // `api` because homebase-upload's public types (e.g. PayloadBundle exposing
            // PayloadFile/ThumbnailFile) reference homebase-api types in their signatures.
            api(project(":homebase-api"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }

    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
}
