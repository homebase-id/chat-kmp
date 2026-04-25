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
        namespace = "id.homebase.chat"
        compileSdk = libs.versions.android.targetSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
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
            baseName = "homebase-chatKit"
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
            implementation(libs.navigation.compose)
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
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.ui)
            implementation(libs.androidx.ui.tooling)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.vlcj)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.jetbrains.compose.ui.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.sqldelight.sqlite.driver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.crypt)
            implementation(libs.kotlinx.coroutines.test)
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

// Create platform-specific JARs that only include native libs for that platform

// Define a custom attribute to identify the target platform
val targetPlatformAttribute = Attribute.of("targetPlatform", String::class.java)

val platformConfigs = mapOf(
    "LinuxX64" to "linux-x64",
    "LinuxArm64" to "linux-arm64",
    "MacosX64" to "macos-x64",
    "MacosArm64" to "macos-arm64",
    "WindowsX64" to "windows-x64"
)

val platformJarTasks = mutableListOf<TaskProvider<Jar>>()

platformConfigs.forEach { (platformName, resourcePath) ->
    val taskName = "jvmJar$platformName"

    // Create the JAR task
    val jarTask = tasks.register<Jar>(taskName) {
        // Get the base jvmJar task
        val baseJar = tasks.named<Jar>("jvmJar")

        // Copy all compiled classes from the base JAR
        from(baseJar.map { zipTree(it.archiveFile) }) {
            exclude("ffmpeg/**")  // Exclude all ffmpeg directories
        }

        // Add only the platform-specific ffmpeg resources
        from(kotlin.sourceSets.named("jvmMain").map { sourceSet ->
            sourceSet.resources.sourceDirectories.files.map { dir ->
                fileTree(dir) {
                    include("ffmpeg/$resourcePath/**")
                }
            }
        })

        archiveClassifier.set(platformName.lowercase())

        // Make this task depend on the base jvmJar
        dependsOn(baseJar)
    }

    platformJarTasks.add(jarTask)

    // Create a consumable configuration for this platform
    val configurationName = "jvmJar$platformName"
    configurations.create(configurationName) {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes {
            attribute(targetPlatformAttribute, platformName)
        }
    }

    // Add the JAR as an artifact to the configuration
    artifacts.add(configurationName, jarTask)
}

// Ensure all platform JARs are built when building the module
tasks.named("assemble") {
    dependsOn(platformJarTasks)
}

// Also wire them up to the jvmJar task for good measure
tasks.named("jvmJar") {
    finalizedBy(platformJarTasks)
}

// `withHostTest {}` is enabled above so AGP 9.2 stops warning about commonTest
// with no Android host-test runner. Compose UI tests in commonTest use a
// Skiko-backed implementation that doesn't compose against the mocked Android
// host-test classpath, so disable the task until those tests are migrated
// to jvmTest/.
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    enabled = false
}
