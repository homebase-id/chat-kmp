import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    applyDefaultHierarchyTemplate()

    // ✅ GLOBAL opt-ins for ALL source sets & targets
    sourceSets.all {
        languageSettings.apply {
            optIn("kotlin.uuid.ExperimentalUuidApi")
            optIn("kotlin.io.encoding.ExperimentalEncodingApi")
            optIn("kotlinx.serialization.ExperimentalSerializationApi")
            optIn("kotlin.time.ExperimentalTime")
            optIn("dev.whyoleg.cryptography.DelicateCryptographyApi")
        }
    }

    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    android {
        namespace = "id.homebase.api"
        compileSdk = libs.versions.android.targetSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true
        withHostTest {}
        // Enables `src/androidInstrumentedTest/` for emulator-on-device tests
        // (currently used by CompressVideoAndroidInstrumentedTest, which is
        // @Ignore'd until CI emulator infra lands). Locally runnable with:
        //   ./gradlew homebase-api:connectedAndroidTest
        withDeviceTest {}
    }

    jvm() {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

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
            baseName = "homebase-api"
            isStatic = true
        }
    }

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.atomicfu)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kermit)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.encoding)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.navigation.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.immutableCollections)
            implementation(libs.coil3)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
        }
        androidMain.dependencies {
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.exifinterface)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.browser)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.ui)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.android.database.sqlcipher)
            implementation(libs.ffmpeg.kit)
            implementation(libs.smart.exception.java)
            // MP4 atom-tree manipulation. Used by Mp4LocationStripper to drop
            // EXIF / GPS location atoms from camera-recorded MP4s when the
            // input passes through compressVideo's already-optimal check
            // without re-encoding (re-encode naturally drops the atoms).
            implementation(libs.mp4parser.isoparser)
            implementation(libs.androidsvg)
        }
        nativeMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }

        // Uncomment when enabling the wasmJs target (post-pre-flight),
        // paired with the `wasmJs { browser() }` block above. ktor-client-js
        // provides the browser engine (fetch/WebSocket); WebWorkerDriver
        // runs sql.js (SQLite-compiled-to-WASM) inside a Web Worker — see
        // `DatabaseDriverFactory.web.kt` for the constructor shape.
//        wasmJsMain.dependencies {
//            implementation(libs.ktor.client.js)
//            implementation(libs.sqldelight.web.worker.driver)
//        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.sqlite.driver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.crypt)

            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.html.builder)

            implementation(libs.kotlinx.html.jvm)

            implementation(libs.metadata.extractor)
        }

        // Provide Skia native binaries for JVM image tests (platform-specific)
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        val desktopDep = when {
            osName.contains("win") -> libs.jetbrains.compose.desktop.jvm.windows.x64
            osName.contains("mac") && osArch.contains("aarch64") -> libs.jetbrains.compose.desktop.jvm.macos.arm64
            osName.contains("mac") -> libs.jetbrains.compose.desktop.jvm.macos.x64
            osArch.contains("aarch64") || osArch.contains("arm64") -> libs.jetbrains.compose.desktop.jvm.linux.arm64
            else -> libs.jetbrains.compose.desktop.jvm.linux.x64
        }
        jvmTest.dependencies {
            implementation(desktopDep)
        }
        // Android host tests run on the JVM with android.jar stubs — the real
        // AndroidSqliteDriver would throw "Stub!" at runtime, so we use the
        // JDBC driver the same way jvmMain does.
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.crypt)
        }
        // Android instrumented (on-device) tests for things that need real
        // platform implementations — e.g. CompressVideoAndroidInstrumentedTest
        // exercises the MediaCodec transcode path, which needs real codec
        // hardware to load. Currently @Ignore'd until CI emulator infra is
        // wired up. Source set named `androidDeviceTest` per AGP 9 KMP
        // convention (vs the older `androidInstrumentedTest`).
        getByName("androidDeviceTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.junit)
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

sqldelight {
    linkSqlite.set(false)
    databases {
        create("OdinDatabase") {
            packageName.set("id.homebase.api.sync.database")
            dialect(libs.sqldelight.sqlite338.dialect)
        }
    }
}