import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets.all {
        languageSettings.apply {
            optIn("kotlin.uuid.ExperimentalUuidApi")
            optIn("kotlin.io.encoding.ExperimentalEncodingApi")
            optIn("kotlinx.serialization.ExperimentalSerializationApi")
            optIn("kotlin.time.ExperimentalTime")
            optIn("dev.whyoleg.cryptography.DelicateCryptographyApi")
        }
    }
    sourceSets.matching { it.name.endsWith("Test") }.configureEach {
        languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    android {
        namespace = "id.homebase.notifshared"
        compileSdk = libs.versions.android.targetSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    // Leaf framework linked into the iOS NSE, which has a ~24 MB RAM budget: this module reaches
    // homebase-api only for the one-header fetch + decrypt, never its DB, DI or image caches.
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "HomebaseNotifKit"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        // homebase-api's wasm output needs the skiko runtime (Compose plugin) to even load, which
        // this NSE-linked module must not carry; the preview is an iOS push path, so it skips web.
        val nonWebMain = create("nonWebMain") { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(nonWebMain)
        jvmMain.get().dependsOn(nonWebMain)
        nativeMain.get().dependsOn(nonWebMain)
        val nonWebTest = create("nonWebTest") { dependsOn(commonTest.get()) }
        getByName("androidHostTest").dependsOn(nonWebTest)
        jvmTest.get().dependsOn(nonWebTest)
        nativeTest.get().dependsOn(nonWebTest)

        nonWebMain.dependencies {
            implementation(project(":homebase-api"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        nonWebTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
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
