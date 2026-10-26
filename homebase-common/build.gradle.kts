import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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

    sourceSets.matching { it.name.startsWith("wasmJs") }.configureEach {
        languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
    }
    sourceSets.matching { s -> listOf("native", "apple", "ios").any { s.name.startsWith(it) } }.configureEach {
        languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
    }
    sourceSets.matching { it.name.endsWith("Test") }.configureEach {
        languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    android {
        namespace = "id.homebase.common"
        compileSdk = libs.versions.android.targetSdk.get().toInt()
        compileSdkExtension = 19
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true
        withHostTest {}
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

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
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }

    sourceSets {
        // Shared Skia-backed implementations for the targets that bundle skiko:
        // Desktop/JVM, iOS/native, and Web/wasmJs. Android is intentionally
        // excluded — it uses Coil's android.graphics-backed decoders (coil-gif
        // animates GIFs there via AnimatedImageDecoder; see PR #663). Lets the
        // in-house animated-GIF/WebP Skia decoder live in one place instead of
        // three near-identical per-platform copies.
        val skiaMain = create("skiaMain") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(skiaMain)
        nativeMain.get().dependsOn(skiaMain)
        wasmJsMain.get().dependsOn(skiaMain)

        val cameraStubMain = create("cameraStubMain") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(cameraStubMain)
        wasmJsMain.get().dependsOn(cameraStubMain)

        // The paste menu shared by every target but iOS, which takes UIKit's own paste: slot.
        val nonIosPasteMain = create("nonIosPasteMain") { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(nonIosPasteMain)
        jvmMain.get().dependsOn(nonIosPasteMain)
        wasmJsMain.get().dependsOn(nonIosPasteMain)

        // FileKit's PlatformFile.path and lastModified() exist on every target but the web.
        val nonWebMain = create("nonWebMain") { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(nonWebMain)
        jvmMain.get().dependsOn(nonWebMain)
        nativeMain.get().dependsOn(nonWebMain)

        // Tests that build a PlatformFile from a path, which the web has no constructor for.
        val nonWebTest = create("nonWebTest") { dependsOn(commonTest.get()) }
        getByName("androidHostTest").dependsOn(nonWebTest)
        jvmTest.get().dependsOn(nonWebTest)
        nativeTest.get().dependsOn(nonWebTest)

        nonWebMain.dependencies {
            implementation(libs.kermit.io)
            // No wasmJs artifact, so it stays off commonMain; api for androidApp/desktopApp/iOS export.
            api(libs.kmpnotifier)
        }

        commonMain.dependencies {
            api(project(":homebase-api"))
            api(project(":homebase-notifshared"))

            implementation(libs.atomicfu)
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.resources)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material3.adaptive)
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
            api(libs.zoomimage.compose.coil3)
            implementation(libs.kermit)
            api(libs.filekit.core)
            api(libs.koin.core)
            api(libs.koin.compose)
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
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.video)
            implementation(libs.androidx.camera.compose)
            implementation(libs.androidx.browser)
            implementation(libs.androidx.pdf.viewer)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.accompanist.permissions)
            implementation(libs.firebase.crashlytics)
            api(libs.coil3.video)
            // coil-gif is an Android-only aar in Coil 3.4.0 and Coil has no Skia animated decoder,
            // so the other targets animate through our AnimatedSkiaDecoder in skiaMain.
            api(libs.coil3.gif)
            // Location add-on tracker: fused provider (batched background
            // PendingIntent updates) + app-foreground observation.
            implementation(libs.play.services.location)
            implementation(libs.androidx.lifecycle.process)
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
            implementation(libs.pdfbox)
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
        // Uncomment when enabling the wasmJs target (post-pre-flight),
        // paired with the `wasmJs { browser() }` block above.
        // ktor-client-js provides the browser engine (fetch/WebSocket).
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
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
