import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.buildConfigPlugin)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "id.homebase.resources"
    nameOfResClass = "MR"
    generateResClass = auto
}

buildConfig {
    buildConfigField("APP_BUILD_TIME", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
    useKotlinOutput { internalVisibility = false }
}

kotlin {
    applyDefaultHierarchyTemplate()

    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    android {
        namespace = "id.homebase.common"
        compileSdk = libs.versions.android.targetSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true
        withHostTest {}
    }

    jvm()

//    @OptIn(ExperimentalWasmDsl::class)
//    wasmJs {
//        browser()
//        binaries.executable()
//    }

    // For iOS targets, this is also where you should
    // configure native binary output. For more information, see:
    // https://kotlinlang.org/docs/multiplatform-build-native-binaries.html#build-xcframeworks

    // A step-by-step guide on how to include this library in an XCode
    // project can be found here:
    // https://developer.android.com/kotlin/multiplatform/migrate
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "homebase-commonKit"
            isStatic = true
            export(libs.kmpnotifier)
        }
    }

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":homebase-api"))

            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.resources)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material.icons.extended)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.immutableCollections)
            implementation(libs.kotlinx.io.core)
            implementation(libs.multiplatform.settings)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.logging)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.richeditor.compose)
            api(libs.coil3)
            api(libs.coil3.compose)
            api(libs.coil3.network)
            api(libs.coil3.svg)
            implementation(libs.kermit)
            implementation(libs.kermit.io)
            api(libs.filekit.core)
            api(libs.koin.core)
            api(libs.koin.compose)
            api(libs.kmpnotifier)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.jetbrains.compose.ui.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.konsist)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.browser)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.accompanist.permissions)
            implementation(libs.firebase.crashlytics)
            api(libs.coil3.video)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.io.core.jvm)
            implementation(libs.nucleus.notification.common)
            implementation(libs.nucleus.notification.windows)
            implementation(libs.nucleus.notification.macos)
            implementation(libs.nucleus.notification.linux)
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
// `withHostTest {}` is enabled above so AGP 9.2 stops warning about commonTest
// with no Android host-test runner. Compose UI tests in commonTest use a
// Skiko-backed implementation that doesn't compose against the mocked Android
// host-test classpath, so disable the task until those tests are migrated
// to jvmTest/.
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    enabled = false
}
