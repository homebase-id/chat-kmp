import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets.matching { it.name.startsWith("wasmJs") }.configureEach {
        languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
    }
    sourceSets.matching { s -> listOf("native", "apple", "ios").any { s.name.startsWith(it) } }.configureEach {
        languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
        languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
    }
    sourceSets.matching { it.name.endsWith("Test") }.configureEach {
        languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    android {
        namespace = "id.homebase.photobase"
        compileSdk {
            version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt() }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest { isIncludeAndroidResources = true }
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    // No framework: an `implementation` dependency of homebase-core, kept off the Swift surface.
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":homebase-api"))
            api(project(":homebase-upload"))
            implementation(project(":homebase-common"))
            implementation(project(":homebase-chat"))

            implementation(libs.kermit)
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.resources)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material.icons.extended)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.work.runtime)
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.work.testing)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
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
