plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinSerialization)
}

sqldelight {
    // Necessary to fix Gradle intellij build error => Collection is Empty.
    databases {
        create("Dummy") {
            packageName = "id.homebase.dummy"
        }
    }
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "id.homebase.core"
        compileSdk = libs.versions.android.targetSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

//    @OptIn(ExperimentalWasmDsl::class)
//    wasmJs {
//        browser()
//        binaries.executable()
//    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            linkerOpts("-lsqlite3")
            linkerOpts("-lz", "-lbz2", "-liconv")

            freeCompilerArgs += listOf("-Xbinary=bundleId=id.homebase.core")
            // Export homebase-api to make FFmpegKitBridge accessible from Swift
            export(project(":homebase-api"))
            export(project(":homebase-common"))
            export(libs.kmpnotifier)
        }
    }

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            // Use api so it can be exported to Swift
            api(project(":homebase-api"))
            api(project(":homebase-common"))
            implementation(project(":homebase-auth"))
            implementation(project(":homebase-chat"))

            implementation(libs.ktor.client.core)
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.resources)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material.icons.extended)
            implementation(libs.jetbrains.compose.material3.adaptive)
            implementation(libs.jetbrains.compose.ui.backhandler)
            implementation(libs.jetbrains.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.atomicfu)
            implementation(libs.kotlinx.immutableCollections)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kermit)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.navigation.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.multiplatform.settings)
            implementation(libs.richeditor.compose)
            implementation(libs.composenativewebview)
        }
        nativeMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.mock)
            implementation(libs.jetbrains.compose.ui.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
        iosTest.dependencies {
            implementation(libs.sqldelight.native.driver)
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

// Skip flaky navigation lifecycle tests in CI (headless environment has stricter lifecycle management)
tasks.withType<Test>().configureEach {
    val isCI = System.getenv("CI")?.toBoolean() ?: false
    if (isCI) {
        filter {
            // These tests access NavController back stack entries outside composition,
            // which triggers IllegalStateException in headless test environments
            excludeTestsMatching("id.homebase.core.ui.navigation.NotificationTapColdStartTest.warm_start_notification_tap_from_detail_navigates_via_chatlist")
            excludeTestsMatching("id.homebase.core.ui.navigation.NotificationTapColdStartTest.broken_top_only_gate_hangs_when_on_detail")
        }
    }
}
