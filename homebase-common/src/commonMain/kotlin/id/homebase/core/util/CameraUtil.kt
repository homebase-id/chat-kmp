package id.homebase.core.util

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile

@Composable
expect fun rememberCameraManager(onResult: (PlatformFile?) -> Unit): PlatformCameraManager

interface PlatformCameraManager {
    fun launch()
}