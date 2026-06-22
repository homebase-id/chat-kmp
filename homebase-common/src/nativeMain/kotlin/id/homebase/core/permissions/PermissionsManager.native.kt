package id.homebase.core.permissions

import androidx.compose.runtime.Composable
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Foundation.NSURL
import platform.darwin.NSObject
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
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.CoreMotion.CMAuthorizationStatusAuthorized
import platform.CoreMotion.CMPedometer
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
actual fun createPermissionsManager(onPermissionResult: (PermissionType, PermissionStatus, Boolean) -> Unit): PermissionsManager {
    return IOSPermissionsManager(onPermissionResult)
}

/**
 * Process-wide CLLocationManager used only for authorization requests/reads.
 * A singleton (rather than per-[IOSPermissionsManager]) because the delegate
 * must stay alive until the user answers the system prompt, while
 * [createPermissionsManager] returns a fresh manager on every composition.
 */
internal object LocationPermissionRequester {
    private var pending: ((CLAuthorizationStatus) -> Unit)? = null

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            val status = manager.authorizationStatus
            // Fires once when the delegate is first assigned; ignore the
            // not-determined report and wait for the user's answer.
            if (status == kCLAuthorizationStatusNotDetermined) return
            pending?.invoke(status)
            pending = null
        }
    }

    private val manager = CLLocationManager().apply { delegate = this@LocationPermissionRequester.delegate }

    val status: CLAuthorizationStatus get() = manager.authorizationStatus

    fun requestWhenInUse(onResult: (CLAuthorizationStatus) -> Unit) {
        val current = status
        if (current != kCLAuthorizationStatusNotDetermined) {
            onResult(current)
            return
        }
        pending = onResult
        manager.requestWhenInUseAuthorization()
    }

    fun requestAlways(onResult: (CLAuthorizationStatus) -> Unit) {
        when (val current = status) {
            kCLAuthorizationStatusAuthorizedAlways,
            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted -> {
                onResult(current)
                return
            }
        }
        // Valid from NotDetermined (single combined prompt) or WhenInUse (the
        // one-time "Change to Always Allow?" escalation). If the user picks
        // "Keep Only While Using" iOS reports no change — callers re-check
        // on resume, so a silently dropped callback is fine.
        pending = onResult
        manager.requestAlwaysAuthorization()
    }
}

/**
 * Core Motion (pedometer) authorization. The first pedometer query triggers the
 * system motion prompt; the auth status afterward reflects the user's choice.
 */
private object MotionPermissionRequester {
    fun isAuthorized(): Boolean =
        CMPedometer.authorizationStatus() == CMAuthorizationStatusAuthorized

    fun request(onResult: (Boolean) -> Unit) {
        if (!CMPedometer.isStepCountingAvailable()) {
            onResult(false)
            return
        }
        val now = NSDate()
        val from = NSDate.dateWithTimeIntervalSince1970(now.timeIntervalSince1970 - 1.0)
        CMPedometer().queryPedometerDataFromDate(from, now) { _, _ ->
            onResult(CMPedometer.authorizationStatus() == CMAuthorizationStatusAuthorized)
        }
    }
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

            PermissionType.RECORD_AUDIO -> {
                val status: AVAuthorizationStatus =
                    AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio)
                askAudioPermission(status, permission, onPermissionResult)
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

            PermissionType.LOCATION -> {
                LocationPermissionRequester.requestWhenInUse { status ->
                    val granted = status == kCLAuthorizationStatusAuthorizedWhenInUse ||
                        status == kCLAuthorizationStatusAuthorizedAlways
                    // iOS never re-prompts after a denial — settings is the only way back.
                    onPermissionResult(
                        permission,
                        if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
                        !granted,
                    )
                }
            }

            PermissionType.LOCATION_ALWAYS -> {
                LocationPermissionRequester.requestAlways { status ->
                    val granted = status == kCLAuthorizationStatusAuthorizedAlways
                    onPermissionResult(
                        permission,
                        if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
                        !granted,
                    )
                }
            }

            PermissionType.ACTIVITY -> {
                // A throwaway pedometer query surfaces the Core Motion prompt;
                // the result is reported via the status read afterwards.
                MotionPermissionRequester.request { granted ->
                    onPermissionResult(
                        permission,
                        if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
                        !granted,
                    )
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

            PermissionType.RECORD_AUDIO -> {
                val status: AVAuthorizationStatus =
                    AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio)
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

            PermissionType.LOCATION -> {
                val status = LocationPermissionRequester.status
                status == kCLAuthorizationStatusAuthorizedWhenInUse ||
                    status == kCLAuthorizationStatusAuthorizedAlways
            }

            PermissionType.LOCATION_ALWAYS -> {
                LocationPermissionRequester.status == kCLAuthorizationStatusAuthorizedAlways
            }

            PermissionType.ACTIVITY -> MotionPermissionRequester.isAuthorized()
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
                onPermissionStatus(permission, PermissionStatus.DENIED, true)
            }
        }
    }

    private fun askAudioPermission(
        status: AVAuthorizationStatus,
        permission: PermissionType,
        onPermissionStatus: (PermissionType, PermissionStatus, Boolean) -> Unit
    ) {
        when (status) {
            AVAuthorizationStatusAuthorized -> {
                onPermissionStatus(permission, PermissionStatus.GRANTED, false)
            }

            AVAuthorizationStatusNotDetermined -> {
                return AVCaptureDevice.requestAccessForMediaType(AVMediaTypeAudio) { isGranted ->
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

            else -> error("Unknown audio status $status")
        }
    }
}
