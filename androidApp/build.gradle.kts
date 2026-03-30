import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.playPublisherPlugin)
    alias(libs.plugins.firebaseCrashlytics)
}

val versionProps = Properties()
versionProps.load(rootProject.file("gradle/version.properties").inputStream())

play {
    track.set("internal")
    serviceAccountCredentials.set(file("../google-play-key.json"))
}

android {
    namespace = "id.homebase.feed"
    compileSdk {
        version = release(libs.versions.android.targetSdk.get().toInt())
    }

    defaultConfig {
        applicationId = "id.homebase.feed"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = (project.findProperty("VERSION_CODE") as String?)?.toInt()
            ?: versionProps.getProperty("version.code.base").toInt()
        versionName = project.findProperty("VERSION_NAME") as String?
            ?: versionProps.getProperty("version.name")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    signingConfigs {
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeFile = file("../buildsystem/debug.keystore")
            storePassword = "android"
        }
        create("release") {
            val keystorePath = System.getenv("SIGNING_KEYSTORE_FILE_PATH")

            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            } else {
                storeFile = file("../buildsystem/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("dev") {
            storeFile = file("../buildsystem/keystore-dev")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = "homebase-dev"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")

        }
    }

    buildTypes {
        release {
            // Enables code shrinking, obfuscation, and optimization for only
            // your project's release build type.
            isMinifyEnabled = true

            // Enables resource shrinking, which is performed by the
            // Android Gradle plugin.
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = signingConfigs.getByName("release")
        }

        create("dev") {
            applicationIdSuffix = ".dev"

            // Enables code shrinking, obfuscation, and optimization for only
            // your project's release build type.
            isMinifyEnabled = true

            // Enables resource shrinking, which is performed by the
            // Android Gradle plugin.
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = signingConfigs.getByName("dev")
        }

        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")

        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":homebase-common"))
    implementation(project(":homebase-core"))
    implementation(project(":homebase-chat"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.jetbrains.compose.material.icons.extended)

    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.smart.exception.java)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.filekit.dialogs.compose)
    implementation(libs.kermit)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.sharetarget)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)

    debugImplementation(libs.androidx.ui.tooling)
}
