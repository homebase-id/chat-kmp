package id.homebase.chat.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSError
import platform.darwin.NSObject

private const val TAG = "LocationLauncher.ios"

@Composable
actual fun rememberCurrentLocationLauncher(
    onResult: (LocationResult) -> Unit,
): LocationLauncher {
    val onResultState = rememberUpdatedState(onResult)
    val holder = remember { LocationLauncherHolder() }

    DisposableEffect(holder) {
        onDispose { holder.invalidate() }
    }

    return remember(holder) {
        object : LocationLauncher {
            override fun launch() {
                Logger.d(tag = TAG) { "launch" }
                holder.start { result -> onResultState.value(result) }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class LocationLauncherHolder {
    private var manager: CLLocationManager? = null
    private var delegate: Delegate? = null
    private var pendingCallback: ((LocationResult) -> Unit)? = null
    private var invalidated = false

    fun start(callback: (LocationResult) -> Unit) {
        if (invalidated) {
            callback(LocationResult.Unavailable); return
        }
        pendingCallback = callback

        val mgr = manager ?: CLLocationManager().also {
            manager = it
            it.desiredAccuracy = kCLLocationAccuracyHundredMeters
        }
        if (delegate == null) {
            val del = Delegate(this)
            delegate = del
            mgr.delegate = del
        }

        when (mgr.authorizationStatus) {
            kCLAuthorizationStatusNotDetermined -> {
                Logger.d(tag = TAG) { "auth not determined → requesting" }
                mgr.requestWhenInUseAuthorization()
            }
            kCLAuthorizationStatusAuthorizedWhenInUse,
            kCLAuthorizationStatusAuthorizedAlways -> {
                Logger.d(tag = TAG) { "auth granted → requesting location" }
                mgr.requestLocation()
            }
            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted -> {
                Logger.d(tag = TAG) { "auth denied/restricted" }
                deliver(LocationResult.PermissionDenied)
            }
            else -> mgr.requestWhenInUseAuthorization()
        }
    }

    fun invalidate() {
        invalidated = true
        manager?.delegate = null
        manager = null
        delegate = null
        pendingCallback = null
    }

    fun deliver(result: LocationResult) {
        val cb = pendingCallback ?: return
        pendingCallback = null
        cb(result)
    }

    fun handleAuthorizationChange(status: CLAuthorizationStatus) {
        if (invalidated || pendingCallback == null) return
        when (status) {
            kCLAuthorizationStatusAuthorizedWhenInUse,
            kCLAuthorizationStatusAuthorizedAlways -> {
                Logger.d(tag = TAG) { "auth changed → granted, requesting location" }
                manager?.requestLocation()
            }
            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted -> {
                Logger.d(tag = TAG) { "auth changed → denied" }
                deliver(LocationResult.PermissionDenied)
            }
        }
    }

    private class Delegate(
        private val holder: LocationLauncherHolder,
    ) : NSObject(), CLLocationManagerDelegateProtocol {

        override fun locationManager(
            manager: CLLocationManager,
            didUpdateLocations: List<*>,
        ) {
            val location = didUpdateLocations.firstOrNull() as? CLLocation
            if (location == null) {
                Logger.w(tag = TAG) { "didUpdateLocations: empty list" }
                holder.deliver(LocationResult.Unavailable); return
            }
            val accuracy = location.horizontalAccuracy.takeIf { it >= 0 }?.toFloat()
            location.coordinate.useContents {
                Logger.d(tag = TAG) { "didUpdateLocations: lat=$latitude lon=$longitude" }
                holder.deliver(
                    LocationResult.Success(
                        LocationFix(
                            latitude = latitude,
                            longitude = longitude,
                            accuracyMeters = accuracy,
                        )
                    )
                )
            }
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            Logger.w(tag = TAG) {
                "CLLocationManager didFailWithError: ${didFailWithError.localizedDescription}"
            }
            holder.deliver(LocationResult.Unavailable)
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            holder.handleAuthorizationChange(manager.authorizationStatus)
        }
    }
}
