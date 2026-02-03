package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitCameraFacing
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher

@Composable
actual fun rememberCameraManager(onResult: (PlatformFile?) -> Unit): PlatformCameraManager {
    val launcher = rememberCameraPickerLauncher { file ->
        onResult(file)
    }
    return remember {
        object : PlatformCameraManager {
            override fun launch() {
                launcher.launch(cameraFacing = FileKitCameraFacing.Back)
            }
        }
    }
}