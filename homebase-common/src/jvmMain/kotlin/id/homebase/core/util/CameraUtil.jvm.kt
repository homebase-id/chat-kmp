package id.homebase.core.util

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile

@Composable
actual fun rememberCameraManager(onResult: (PlatformFile?) -> Unit): PlatformCameraManager {
    return object : PlatformCameraManager {
        override fun launch() {
            // No-op on web
        }
    }
}