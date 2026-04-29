package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerMediaURL
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberCameraManager(onResult: (PlatformFile?) -> Unit): PlatformCameraManager {
    val launcher = rememberCameraPickerLauncher { file ->
        onResult(file)
    }
    return remember {
        object : PlatformCameraManager {
            override fun launch() {
                val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
                when (status) {
                    AVAuthorizationStatusAuthorized -> launcher.launch()
                    AVAuthorizationStatusNotDetermined -> {
                        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                            if (granted) {
                                dispatch_async(dispatch_get_main_queue()) {
                                    launcher.launch()
                                }
                            }
                        }
                    }
                    else -> {
                        NSURL.URLWithString(platform.UIKit.UIApplicationOpenSettingsURLString)?.let {
                            UIApplication.sharedApplication.openURL(it, emptyMap<Any?, String>()) {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
actual fun rememberVideoRecorderManager(onResult: (PlatformFile?) -> Unit): PlatformVideoRecorderManager {
    val currentOnResult = rememberUpdatedState(onResult)
    return remember {
        object : PlatformVideoRecorderManager {
            private var delegate: VideoPickerDelegate? = null

            override fun launch() {
                ensureCameraAndMicPermissions {
                    presentVideoPicker()
                }
            }

            private fun ensureCameraAndMicPermissions(onGranted: () -> Unit) {
                val camStatus = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
                when (camStatus) {
                    AVAuthorizationStatusAuthorized -> ensureMicPermission(onGranted)
                    AVAuthorizationStatusNotDetermined -> {
                        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                            if (granted) {
                                ensureMicPermission(onGranted)
                            }
                        }
                    }
                    else -> {
                        NSURL.URLWithString(platform.UIKit.UIApplicationOpenSettingsURLString)?.let {
                            UIApplication.sharedApplication.openURL(it, emptyMap<Any?, String>()) {}
                        }
                    }
                }
            }

            private fun ensureMicPermission(onGranted: () -> Unit) {
                val micStatus = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio)
                when (micStatus) {
                    AVAuthorizationStatusAuthorized -> {
                        dispatch_async(dispatch_get_main_queue()) { onGranted() }
                    }
                    AVAuthorizationStatusNotDetermined -> {
                        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeAudio) { granted ->
                            if (granted) {
                                dispatch_async(dispatch_get_main_queue()) { onGranted() }
                            }
                        }
                    }
                    else -> {
                        NSURL.URLWithString(platform.UIKit.UIApplicationOpenSettingsURLString)?.let {
                            UIApplication.sharedApplication.openURL(it, emptyMap<Any?, String>()) {}
                        }
                    }
                }
            }

            private fun presentVideoPicker() {
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