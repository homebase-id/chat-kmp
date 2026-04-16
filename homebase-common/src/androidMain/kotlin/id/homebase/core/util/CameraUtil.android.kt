package id.homebase.core.util

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitCameraFacing
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import java.io.File

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

@Composable
actual fun rememberVideoRecorderManager(onResult: (PlatformFile?) -> Unit): PlatformVideoRecorderManager {
    val context = LocalContext.current
    val videoUri = remember { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success) {
            videoUri.value?.let { uri ->
                onResult(PlatformFile(uri))
            }
        } else {
            onResult(null)
        }
    }

    return remember {
        object : PlatformVideoRecorderManager {
            override fun launch() {
                val file = File(
                    context.cacheDir,
                    "video_${System.currentTimeMillis()}.mp4"
                )
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                videoUri.value = uri
                launcher.launch(uri)
            }
        }
    }
}