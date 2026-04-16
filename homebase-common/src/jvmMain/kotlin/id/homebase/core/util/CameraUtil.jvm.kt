package id.homebase.core.util

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile

@Composable
actual fun rememberCameraManager(onResult: (PlatformFile?) -> Unit): PlatformCameraManager {
    return object : PlatformCameraManager {
        override fun launch() {
            // No-op on desktop
        }
    }
}

@Composable
actual fun rememberVideoRecorderManager(onResult: (PlatformFile?) -> Unit): PlatformVideoRecorderManager {
    return object : PlatformVideoRecorderManager {
        override fun launch() {
            // No-op on desktop
        }
    }
}