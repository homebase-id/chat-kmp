package id.homebase.core.permissions

import androidx.compose.runtime.Composable
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary
import platform.PhotosUI.presentLimitedLibraryPickerFromViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
actual fun createPermissionsManager(onPermissionResult: (PermissionType, PermissionStatus, Boolean) -> Unit): PermissionsManager {
    return IOSPermissionsManager(onPermissionResult)
}

class IOSPermissionsManager(val onPermissionResult: (PermissionType, PermissionStatus, Boolean) -> Unit) :
    PermissionsManager {
    override fun askPermission(permission: PermissionType) {
        when (permission) {
            PermissionType.CAMERA -> {
                val status: AVAuthorizationStatus =
                    AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
                askCameraPermission(status, permission, onPermissionResult)
            }

            PermissionType.GALLERY, PermissionType.GALLERY_LIMITED -> {
                // Use the new API that properly detects limited access
                val status: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
                askGalleryPermission(status, permission, onPermissionResult)
            }

            PermissionType.GALLERY_LIMITED -> {
                // not implemented
            }

            PermissionType.NOTIFICATION -> {
                UNUserNotificationCenter.currentNotificationCenter()
                    .getNotificationSettingsWithCompletionHandler { settings ->
                        val status =
                            settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined
                        askNotificationPermission(status, permission, onPermissionResult)
                    }
            }
        }
    }

    override suspend fun isPermissionGranted(permission: PermissionType): Boolean {
        return when (permission) {
            PermissionType.CAMERA -> {
                val status: AVAuthorizationStatus =
                    AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
                status == AVAuthorizationStatusAuthorized
            }

            PermissionType.GALLERY -> {
                val status: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
                status == PHAuthorizationStatusAuthorized
            }

            PermissionType.GALLERY_LIMITED  -> {
                val status: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
                status == PHAuthorizationStatusLimited
            }

            PermissionType.NOTIFICATION -> suspendCoroutine { cont ->
                UNUserNotificationCenter.currentNotificationCenter()
                    .getNotificationSettingsWithCompletionHandler { settings ->
                        val status = settings?.authorizationStatus
                        val granted =
                            status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional || status == UNAuthorizationStatusEphemeral
                        cont.resume(granted)
                    }
            }
        }
    }

    override fun launchSettings() {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
            UIApplication.sharedApplication.openURL(it, emptyMap<Any?, String>(), {} )
        }
    }

    private fun askCameraPermission(
        status: AVAuthorizationStatus,
        permission: PermissionType,
        onPermissionStatus: (PermissionType, PermissionStatus, Boolean) -> Unit
    ) {
        when (status) {
            AVAuthorizationStatusAuthorized -> {
                onPermissionStatus(permission, PermissionStatus.GRANTED, false)
            }

            AVAuthorizationStatusNotDetermined -> {
                return AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { isGranted ->
                    if (isGranted) {
                        onPermissionStatus(permission, PermissionStatus.GRANTED, false)
                    } else {
                        onPermissionStatus(permission, PermissionStatus.DENIED, false)
                    }
                }
            }

            AVAuthorizationStatusDenied -> {
                onPermissionStatus(permission, PermissionStatus.DENIED, false)
            }

            else -> error("Unknown camera status $status")
        }
    }

    private fun askGalleryPermission(
        status: PHAuthorizationStatus,
        permission: PermissionType,
        onPermissionStatus: (PermissionType, PermissionStatus, Boolean) -> Unit
    ) {
        when (status) {
            PHAuthorizationStatusAuthorized -> {
                onPermissionStatus(permission, PermissionStatus.GRANTED, false)
            }

            PHAuthorizationStatusLimited -> {
                // Show picker to select more photos when already in limited mode
                if (permission == PermissionType.GALLERY_LIMITED) {
                    val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController
                    rootViewController?.let {
                        PHPhotoLibrary.sharedPhotoLibrary().presentLimitedLibraryPickerFromViewController(it)
                    }
                }
                onPermissionStatus(permission, PermissionStatus.GRANTED, true)
            }

            PHAuthorizationStatusNotDetermined -> {
                PHPhotoLibrary.requestAuthorization { newStatus ->
                    askGalleryPermission(newStatus, permission, onPermissionStatus)
                }
            }

            PHAuthorizationStatusDenied -> {
                onPermissionStatus(permission, PermissionStatus.DENIED, false)
            }

            else -> error("Unknown gallery status $status")
        }
    }

    private fun askNotificationPermission(
        status: Long,
        permission: PermissionType,
        onPermissionStatus: (PermissionType, PermissionStatus, Boolean) -> Unit
    ) {
        when (status) {
            UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral -> {
                onPermissionStatus(permission, PermissionStatus.GRANTED, false)
            }

            UNAuthorizationStatusNotDetermined -> {
                UNUserNotificationCenter.currentNotificationCenter()
                    .requestAuthorizationWithOptions(
                        options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
                    ) { granted, _ ->
                        onPermissionStatus(
                            permission,
                            if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
                            false
                        )
                    }
            }

            else -> {
                onPermissionStatus(permission, PermissionStatus.DENIED, false)
            }
        }
    }
}
