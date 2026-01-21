plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "id.homebase.android"
    compileSdk {
        version = release(libs.versions.android.targetSdk.get().toInt())
    }

    defaultConfig {
        applicationId = "id.homebase.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jetbrains.compose.material3)

    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.smart.exception.java)
    implementation(libs.androidx.ui.tooling.preview)


    debugImplementation(libs.androidx.ui.tooling)
}