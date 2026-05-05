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
    onResult: (LocationFix?) -> Unit,
): LocationLauncher {
    val onResultState = rememberUpdatedState(onResult)
    val holder = remember { LocationLauncherHolder() }

    DisposableEffect(holder) {
        onDispose { holder.invalidate() }
    }

    return remember(holder) {
        object : LocationLauncher {
            override fun launch() {
                holder.start { fix -> onResultState.value(fix) }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class LocationLauncherHolder {
    private var manager: CLLocationManager? = null
    private var delegate: Delegate? = null
    private var pendingCallback: ((LocationFix?) -> Unit)? = null
    private var invalidated = false

    fun start(callback: (LocationFix?) -> Unit) {
        if (invalidated) {
            callback(null); return
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
            kCLAuthorizationStatusNotDetermined -> mgr.requestWhenInUseAuthorization()
            kCLAuthorizationStatusAuthorizedWhenInUse,
            kCLAuthorizationStatusAuthorizedAlways -> mgr.requestLocation()
            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted -> deliver(null)
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

    fun deliver(fix: LocationFix?) {
        val cb = pendingCallback ?: return
        pendingCallback = null
        cb(fix)
    }

    fun handleAuthorizationChange(status: CLAuthorizationStatus) {
        if (invalidated || pendingCallback == null) return
        when (status) {
            kCLAuthorizationStatusAuthorizedWhenInUse,
            kCLAuthorizationStatusAuthorizedAlways -> manager?.requestLocation()
            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted -> deliver(null)
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
                holder.deliver(null); return
            }
            val accuracy = location.horizontalAccuracy.takeIf { it >= 0 }?.toFloat()
            location.coordinate.useContents {
                holder.deliver(
                    LocationFix(
                        latitude = latitude,
                        longitude = longitude,
                        accuracyMeters = accuracy,
                    )
                )
            }
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            Logger.w(tag = TAG) {
                "CLLocationManager didFailWithError: ${didFailWithError.localizedDescription}"
            }
            holder.deliver(null)
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            holder.handleAuthorizationChange(manager.authorizationStatus)
        }
    }
}
