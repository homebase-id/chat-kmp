import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.util.Properties

val versionProps = Properties()
versionProps.load(rootProject.file("gradle/version.properties").inputStream())

// Use version from command line (-Pversion.name=...) if provided, otherwise use properties file
val versionName: String = findProperty("version.name")?.toString()
    ?: versionProps.getProperty("version.name")

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.conveyorPlugin)
}

version = versionName

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

    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":homebase-api"))
            implementation(project(":homebase-common"))
            implementation(project(":homebase-core")) {
                exclude(module = "homebase-chat")
            }

            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.resources)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material.icons.extended)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.multiplatform.settings)
            implementation(libs.koin.core)
            implementation(libs.filekit.core)
            implementation(libs.kmpnotifier)
            implementation(libs.kermit)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.sqldelight.sqlite.driver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.crypt)
            implementation(libs.composenativetray)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.datetime)
        }
    }
}

dependencies {
    // Platform-specific homebase-chat JARs
    linuxAmd64(project(":homebase-chat", configuration = "jvmJarLinuxX64"))
    linuxAarch64(project(":homebase-chat", configuration = "jvmJarLinuxArm64"))
    macAmd64(project(":homebase-chat", configuration = "jvmJarMacosX64"))
    macAarch64(project(":homebase-chat", configuration = "jvmJarMacosArm64"))
    windowsAmd64(project(":homebase-chat", configuration = "jvmJarWindowsX64"))

    // Use the configurations created by the Conveyor plugin to tell Gradle/Conveyor where to find the artifacts for each platform.
    linuxAmd64(libs.jetbrains.compose.desktop.jvm.linux.x64)
    linuxAarch64(libs.jetbrains.compose.desktop.jvm.linux.arm64)
    macAmd64(libs.jetbrains.compose.desktop.jvm.macos.x64)
    macAarch64(libs.jetbrains.compose.desktop.jvm.macos.arm64)
    windowsAmd64(libs.jetbrains.compose.desktop.jvm.windows.x64)
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

// Generate version.properties into JVM resources so JvmPlatformInfo can read it at runtime
val generateVersionProperties by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/version")
    // Resolve values at configuration time to be configuration-cache compatible
    val versionCode = (providers.gradleProperty("VERSION_CODE").orNull)
        ?: versionProps.getProperty("version.code.base")
    val resolvedVersionName = (providers.gradleProperty("version.name").orNull)
        ?: versionProps.getProperty("version.name")
    inputs.property("versionCode", versionCode)
    inputs.property("versionName", resolvedVersionName)
    outputs.dir(outputDir)
    doLast {
        val outFile = outputDir.get().file("version.properties").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText("version.name=$resolvedVersionName\nversion.code=$versionCode\n")
    }
}

kotlin.sourceSets.named("jvmMain") {
    resources.srcDir(generateVersionProperties.map { it.outputs.files.singleFile })
}

compose.desktop {
    application {
        javaHome = System.getenv("JDK_21")
        mainClass = "id.homebase.app.MainKt"

        jvmArgs += listOf(
            "-Dapple.awt.application.appearance=system",
        )

        buildTypes.release.proguard {
            version.set("7.6.1")
            configurationFiles.from(project.file("compose-desktop.pro"))
            obfuscate.set(true)
            optimize.set(false)
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            packageName = "homebase-chat"
            packageVersion = versionName
            description = "Homebase Chat for Desktop"
            vendor = "Homebase"

            modules(
                "java.base",
                "java.compiler",
                "java.instrument",
                "java.management",
                "java.net.http",
                "java.prefs",
                "java.rmi",
                "java.security.jgss",
                "java.sql.rowset",
                "jdk.httpserver",
                "jdk.unsupported",
                "jdk.xml.dom",
                "jdk.security.auth"
            )

            macOS {
                iconFile.set(project.rootProject.file("icons/icon.icns"))  // Path to your .icns file
                packageName = "Homebase Chat"
                bundleID = "id.homebase.feed"

                // Add Info.plist configuration for macOS
                infoPlist {
                    // Microphone permission
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Allow access to the microphone to record audio for message attachment.</string>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.rootProject.file("icons/icon.ico"))  // Path to your .ico file
                menuGroup = "Homebase"
                // see https://wixtoolset.org/documentation/manual/v3/howtos/general/generate_guids.html
                upgradeUuid = "01f4e182-6e97-4a96-83e7-b25800fa9f83"
                dirChooser = true
                perUserInstall = true
            }
            linux {
                iconFile.set(project.rootProject.file("icons/icon.png"))  // Path to your .png file
            }
        }
    }
}
