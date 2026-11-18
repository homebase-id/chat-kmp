// The KMP Android library plugin has no NDK support, so native BLAKE3 is its own Android library.
plugins {
    alias(libs.plugins.androidNativeLibrary)
}

android {
    namespace = "id.homebase.blake3"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt() }
    }
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
