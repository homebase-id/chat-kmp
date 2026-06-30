import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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
            baseName = "ComposeApp"
            isStatic = true
            linkerOpts("-lsqlite3")
            linkerOpts("-lz", "-lbz2", "-liconv")

            freeCompilerArgs += listOf("-Xbinary=bundleId=id.homebase.core")
            // Export homebase-api to make FFmpegKitBridge accessible from Swift
            export(project(":homebase-api"))
            export(project(":homebase-common"))
            export(project(":image-editor-ui"))
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
            api(project(":image-editor-ui"))
            implementation(project(":homebase-upload"))
            implementation(project(":homebase-auth"))
            implementation(project(":homebase-chat"))

            implementation(libs.ktor.client.core)
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui.backhandler)
            implementation(libs.jetbrains.compose.resources)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material.icons.extended)
            implementation(libs.jetbrains.compose.material3.adaptive)
            implementation(libs.jetbrains.compose.ui.backhandler)
            implementation(libs.jetbrains.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
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
            // Drag-to-reorder list (Calvin-LL/Reorderable) — declared here as well as
            // in homebase-chat (its actual home) for the SAME Desktop transitive-strip
            // reason documented for the markdown renderer below; the Poll composer's
            // ReorderableColumn would otherwise NoClassDefFoundError on Desktop.
            implementation(libs.reorderable)
            // mikepenz markdown renderer — declared here, not only in homebase-chat
            // (its actual home), for the SAME reason richeditor is duplicated above:
            // desktopApp consumes homebase-chat via a repackaged per-platform JAR
            // (desktopApp/build.gradle.kts `jvmJarMacosArm64` etc.) that carries only
            // homebase-chat's own classes and STRIPS its external transitive deps.
            // So the renderer's core/m3/coil3 artifacts never reach the Desktop
            // runtime through homebase-chat. homebase-core, by contrast, is consumed
            // by desktopApp as a normal JVM project dependency with full metadata, so
            // listing them here puts them on the Desktop classpath and fixes the
            // NoClassDefFoundError com/mikepenz/markdown/annotator/AnnotatorSettingsKt
            // at first markdown render. (Android/iOS/Web were always fine — they
            // consume homebase-chat normally and got these transitively.)
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.m3)
            implementation(libs.markdown.renderer.coil3)
            implementation(libs.filekit.dialogs.compose)
        }
        nativeMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
            // composenativewebview has no wasmJs artifact — must stay off
            // commonMain. The FeedWebView facade (commonMain) is bridged to
            // kdroidfilter types in `FeedWebView.native.kt`.
            implementation(libs.composenativewebview)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.play.app.update)
            implementation(libs.composenativewebview)
        }
        jvmMain.dependencies {
                implementation(libs.conveyor.control)
                implementation(libs.composenativewebview)
        }
        // Uncomment when enabling the wasmJs target (post-pre-flight),
        // paired with the `wasmJs { browser() }` block above.
        // ktor-client-js provides the browser engine (fetch/WebSocket).
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
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

// `withHostTest {}` is enabled above so AGP 9.2 stops warning about commonTest
// with no Android host-test runner. However, most commonTest files here use
// `runComposeUiTest {}` (compose-ui-test), which resolves to a Skiko-backed
// implementation on JVM but hits null-returning Android API stubs when run
// against the mocked Android host-test classpath. Disable the task until
// those tests are migrated to jvmTest/.
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    enabled = false
}
