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

    android {
        namespace = "id.homebase.auth"
        compileSdk = libs.versions.android.targetSdk.get().toInt()
        compileSdkExtension = 19
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "homebase-authKit"
            isStatic = true
        }
    }

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":homebase-api"))
            implementation(project(":homebase-common"))

            implementation(libs.kermit)
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.resources)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material3.adaptive)
            implementation(libs.jetbrains.compose.material3.adaptive.layout)
            implementation(libs.jetbrains.compose.material3.adaptive.navigation)
            implementation(libs.jetbrains.compose.material.icons.extended)
            implementation(libs.jetbrains.compose.ui.backhandler)
            implementation(libs.jetbrains.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.immutableCollections)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.logging)
            implementation(libs.coil3)
            implementation(libs.coil3.compose)
            implementation(libs.coil3.network)
            implementation(libs.richeditor.compose)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.ui.tooling)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        // Uncomment when enabling the wasmJs target (post-pre-flight),
        // paired with the `wasmJs { browser() }` block above.
        // ktor-client-js provides the browser engine (fetch/WebSocket).
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.jetbrains.compose.ui.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
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
