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

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
    }

    sourceSets {
        commonMain.dependencies {
            // `api` because homebase-upload's public types (e.g. PayloadBundle exposing
            // PayloadFile/ThumbnailFile) reference homebase-api types in their signatures.
            api(project(":homebase-api"))
            // coroutines + kermit + serialization are `implementation` (non-transitive) in
            // homebase-api, so declare them directly here. serialization is needed because
            // homebase-api's @Serializable types (e.g. FileSystemType) expose a Companion whose
            // supertype lives in kotlinx-serialization.
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            // Real in-memory OutboxSync/DB for UploadServiceTest — same JDBC-SQLite wiring
            // homebase-api's own DB tests use (xerial excluded in favour of the crypt fork).
            implementation(libs.sqldelight.sqlite.driver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.crypt)
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
