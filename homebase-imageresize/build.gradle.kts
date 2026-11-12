// The KMP Android library plugin has no NDK support, so the native resizer is its own Android library.
plugins {
    alias(libs.plugins.androidNativeLibrary)
}

android {
    namespace = "id.homebase.imageresize"
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
