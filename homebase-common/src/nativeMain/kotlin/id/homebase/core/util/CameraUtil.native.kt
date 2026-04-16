package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerMediaURL
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.darwin.NSObject

@Composable
actual fun rememberCameraManager(onResult: (PlatformFile?) -> Unit): PlatformCameraManager {
    val launcher = rememberCameraPickerLauncher { file ->
        onResult(file)
    }
    return remember {
        object : PlatformCameraManager {
            override fun launch() {
                launcher.launch()
            }
        }
    }
}

@Composable
actual fun rememberVideoRecorderManager(onResult: (PlatformFile?) -> Unit): PlatformVideoRecorderManager {
    val currentOnResult = rememberUpdatedState(onResult)
    return remember {
        object : PlatformVideoRecorderManager {
            // Retained to prevent garbage collection while picker is presented
            private var delegate: VideoPickerDelegate? = null

            override fun launch() {
                val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return

                val picker = UIImagePickerController()
                picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
                picker.mediaTypes = listOf("public.movie")
                picker.videoQuality = 0L // UIImagePickerControllerQualityTypeHigh

                val d = VideoPickerDelegate { url ->
                    if (url != null) {
                        currentOnResult.value(PlatformFile(url))
                    } else {
                        currentOnResult.value(null)
                    }
                    delegate = null
                }
                delegate = d
                picker.delegate = d

                rootVC.presentViewController(picker, animated = true, completion = null)
            }
        }
    }
}

private class VideoPickerDelegate(
    private val onResult: (NSURL?) -> Unit
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>
    ) {
        val mediaURL = didFinishPickingMediaWithInfo[UIImagePickerControllerMediaURL] as? NSURL
        picker.dismissViewControllerAnimated(true) {
            onResult(mediaURL)
        }
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true) {
            onResult(null)
        }
    }
}