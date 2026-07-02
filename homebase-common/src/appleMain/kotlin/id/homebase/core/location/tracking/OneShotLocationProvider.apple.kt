package id.homebase.core.location.tracking

import co.touchlab.kermit.Logger
import id.homebase.core.permissions.isLocationPermissionGranted
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume

private const val TAG = "OneShotLocation.ios"

@OptIn(ExperimentalForeignApi::class)
actual fun createOneShotLocationProvider(): OneShotLocationProvider = AppleOneShotLocationProvider

/**
 * CLLocationManager one-shot primitives (mirrors the former chat `LocationLauncher.native`, but
 * suspend and feeding a [RawLocationPoint]). The fresh fix runs `requestLocation()` on the main run
 * loop so delegate callbacks fire. The cache-vs-radio policy lives in
 * [OneShotLocationProvider.getCurrentFix] (common); this file is just the platform I/O. Does not
 * request permission (returns null / [GpsFixResult.PermissionDenied] when not authorized).
 */
@OptIn(ExperimentalForeignApi::class)
private object AppleOneShotLocationProvider : OneShotLocationProvider {

    override suspend fun lastKnownFix(): RawLocationPoint? {
        if (!isLocationPermissionGranted()) return null
        return withContext(Dispatchers.Main) {
            CLLocationManager().location?.toRawPoint(fg = true)
        }
    }

    override suspend fun acquireFreshFix(timeoutMs: Long): GpsFixResult {
        if (!isLocationPermissionGranted()) return GpsFixResult.PermissionDenied
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<GpsFixResult> { cont ->
                    val mgr = CLLocationManager()
                    mgr.desiredAccuracy = kCLLocationAccuracyBest
                    val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
                        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                            manager.delegate = null
                            val loc = didUpdateLocations.filterIsInstance<CLLocation>().lastOrNull()
                            if (!cont.isActive) return
                            if (loc == null) {
                                cont.resume(GpsFixResult.Unavailable)
                            } else {
                                cont.resume(GpsFixResult.Success(loc.toRawPoint(fg = true)))
                            }
                        }

                        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                            manager.delegate = null
                            Logger.w(tag = TAG) { "didFailWithError: ${didFailWithError.localizedDescription}" }
                            if (cont.isActive) cont.resume(GpsFixResult.Unavailable)
                        }
                    }
                    mgr.delegate = delegate
                    cont.invokeOnCancellation { mgr.delegate = null }
                    mgr.requestLocation()
                }
            } ?: GpsFixResult.Timeout
        }
    }
}
