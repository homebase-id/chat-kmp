package id.homebase.photobase.backup

import platform.UIKit.UIBackgroundRefreshStatus
import platform.UIKit.UIApplication
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Foundation.thermalState
import platform.Foundation.isLowPowerModeEnabled
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.darwin.dispatch_get_global_queue
import kotlin.coroutines.resume

class IosBackupDeviceProbe : BackupDeviceProbe {

    override suspend fun state(): DeviceState {
        val (hasNetwork, isUnmetered) = networkPath()
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val batteryState = device.batteryState
        val level = device.batteryLevel
        return DeviceState(
            hasNetwork = hasNetwork,
            isUnmetered = isUnmetered,
            isCharging = batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
                batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull,
            isBatteryLow = level in 0f..LOW_BATTERY,
            isLowPowerMode = NSProcessInfo.processInfo.isLowPowerModeEnabled(),
            freeStorageBytes = freeStorageBytes(),
            isOverheated = NSProcessInfo.processInfo.thermalState.value >= NSProcessInfoThermalState.NSProcessInfoThermalStateSerious.value,
        )
    }

    // "Important usage" counts space iOS would purge to make room, which it does for writes like these.
    private fun freeStorageBytes(): Long? {
        val values = NSURL.fileURLWithPath(NSHomeDirectory()).resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), null)
        return (values?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber)?.longLongValue
    }

    override fun libraryAccess(): LibraryAccess = when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)) {
        PHAuthorizationStatusAuthorized -> LibraryAccess.Full
        PHAuthorizationStatusLimited -> LibraryAccess.Limited
        else -> LibraryAccess.None
    }

    override suspend fun isBackgroundRefreshOff(): Boolean = withContext(Dispatchers.Main) {
        UIApplication.sharedApplication.backgroundRefreshStatus != UIBackgroundRefreshStatus.UIBackgroundRefreshStatusAvailable
    }

    // A monitor reports the current path once as soon as it starts.
    private suspend fun networkPath(): Pair<Boolean, Boolean> = suspendCancellableCoroutine { continuation ->
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_queue(monitor, dispatch_get_global_queue(0, 0u))
        nw_path_monitor_set_update_handler(monitor) { path ->
            nw_path_monitor_cancel(monitor)
            if (continuation.isActive) {
                continuation.resume((nw_path_get_status(path) == nw_path_status_satisfied) to !nw_path_is_expensive(path))
            }
        }
        continuation.invokeOnCancellation { nw_path_monitor_cancel(monitor) }
        nw_path_monitor_start(monitor)
    }

    private companion object {
        const val LOW_BATTERY = 0.15f
    }
}
