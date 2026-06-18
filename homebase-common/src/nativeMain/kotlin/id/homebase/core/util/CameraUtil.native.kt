package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.vinceglb.filekit.PlatformFile
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerMediaURL
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS camera (photo). Presents a native [UIImagePickerController] with the camera source —
 * the same approach as [rememberVideoRecorderManager] below. We deliberately do NOT use
 * FileKit's `rememberCameraPickerLauncher` here: its camera presentation opened and then
 * dismissed instantly in-chat on iOS, so the photo path mirrors the working video path.
 *
 * The captured [UIImage] is JPEG-encoded to a temp file and returned as a [PlatformFile];
 * a camera capture has no source URL ([UIImagePickerControllerMediaURL] is movie-only and
 * [UIImagePickerControllerImageURL] is null for fresh captures), so we encode it ourselves.
 */
@Composable
actual fun rememberCameraManager(onResult: (PlatformFile?) -> Unit): PlatformCameraManager {
    // Result lambda is recreated each recomposition (it captures conversation state); keep
    // the latest so a capture that finishes after a recomposition reports to the live one.
    val currentOnResult = rememberUpdatedState(onResult)
    return remember {
        object : PlatformCameraManager {
            // UIImagePickerController.delegate is a weak reference — hold a strong ref here
            // or it would be deallocated and no pick/cancel callback would ever fire.
            private var delegate: PhotoPickerDelegate? = null

            override fun launch() {
                ensureCameraPermission { presentPhotoPicker() }
            }

            private fun ensureCameraPermission(onGranted: () -> Unit) {
                when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
                    AVAuthorizationStatusAuthorized ->
                        dispatch_async(dispatch_get_main_queue()) { onGranted() }

                    AVAuthorizationStatusNotDetermined ->
                        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                            if (granted) dispatch_async(dispatch_get_main_queue()) { onGranted() }
                        }

                    else ->
                        NSURL.URLWithString(platform.UIKit.UIApplicationOpenSettingsURLString)?.let {
                            UIApplication.sharedApplication.openURL(it, emptyMap<Any?, String>()) {}
                        }
                }
            }

            private fun presentPhotoPicker() {
                val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return

                val picker = UIImagePickerController()
                picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
                picker.mediaTypes = listOf("public.image")

                val d = PhotoPickerDelegate { file ->
                    currentOnResult.value(file)
                    delegate = null
                }
                delegate = d
                picker.delegate = d

                rootVC.presentViewController(picker, animated = true, completion = null)
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

/** Encodes a captured [UIImage] to a JPEG temp file and wraps it as a [PlatformFile], or null. */
private fun saveImageToTempFile(image: UIImage): PlatformFile? {
    val data = UIImageJPEGRepresentation(image, 0.9) ?: return null
    val path = NSTemporaryDirectory() + "camera_" + NSUUID().UUIDString + ".jpg"
    return if (data.writeToFile(path, atomically = true)) {
        PlatformFile(NSURL.fileURLWithPath(path))
    } else {
        null
    }
}

private class PhotoPickerDelegate(
    private val onResult: (PlatformFile?) -> Unit
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>
    ) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        picker.dismissViewControllerAnimated(true) {
            onResult(image?.let { saveImageToTempFile(it) })
        }
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true) {
            onResult(null)
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
