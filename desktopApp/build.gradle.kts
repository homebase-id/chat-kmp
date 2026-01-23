import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    applyDefaultHierarchyTemplate()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    // Apply Native-specific opt-ins
    targets.withType<KotlinNativeTarget>().configureEach {
        compilerOptions {
            optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }

    // Global opt-ins
    sourceSets.all {
        languageSettings.apply {
            optIn("kotlin.uuid.ExperimentalUuidApi")
            optIn("kotlin.io.encoding.ExperimentalEncodingApi")
            optIn("kotlinx.serialization.ExperimentalSerializationApi")
            optIn("kotlin.time.ExperimentalTime")
//            optIn("dev.whyoleg.cryptography.DelicateCryptographyApi")
        }
    }
    // Suppress expect/actual classes Beta warning
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":homebase-common"))
            implementation(project(":homebase-core"))
            implementation(project(":homebase-chat"))

            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.resources)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.multiplatform.settings)
            implementation(libs.koin.core)
        }

        jvmMain.dependencies {
            implementation(libs.jetbrains.compose.desktop.jvm.macos.arm64)
            implementation(libs.jetbrains.compose.desktop.jvm.macos.x64)
            implementation(libs.jetbrains.compose.desktop.jvm.windows.x64)
            implementation(libs.jetbrains.compose.desktop.jvm.linux.arm64)
            implementation(libs.jetbrains.compose.desktop.jvm.linux.x64)
            implementation(libs.kotlinx.coroutinesSwing)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.datetime)
        }
    }
}

// Disable allWarningsAsErrors for metadata compilation tasks only
// This works around KLIB duplicate unique_name warnings (known KMP issue: KT-66568)
// https://youtrack.jetbrains.com/issue/KT-66568
// while keeping strict warnings for actual source compilation
tasks.matching { it.name.contains("KotlinMetadata") }.configureEach {
    if (this is org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>) {
        compilerOptions {
            allWarningsAsErrors.set(false)
        }
    }
}

compose.desktop {
    application {
        mainClass = "id.homebase.app.MainKt"
        nativeDistributions {
            macOS {
                iconFile.set(project.rootProject.file("icons/icon.icns"))  // Path to your .icns file
            }
            windows {
                iconFile.set(project.rootProject.file("icons/icon.ico"))  // Path to your .ico file
            }
            linux {
                iconFile.set(project.rootProject.file("icons/icon.png"))  // Path to your .png file
            }
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Homebase Chat"
            packageVersion = "1.0.0"
        }
    }
}