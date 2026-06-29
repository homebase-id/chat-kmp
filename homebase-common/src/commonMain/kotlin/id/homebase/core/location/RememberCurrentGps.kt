package id.homebase.core.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import id.homebase.core.location.tracking.GpsFixResult
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Fires a single current-GPS request (requesting permission first if needed). */
fun interface CurrentGpsLauncher {
    fun launch()
}

/**
 * The UI-side one-shot GPS entry point (replaces the former chat `rememberCurrentLocationLauncher`).
 * On [CurrentGpsLauncher.launch] it ensures location permission via the platform
 * [createPermissionsManager], then calls [LocationService.requestLatestGps] — which routes the fix
 * through the pipeline (last-known + history/relay) so it isn't wasted. The result is delivered to
 * [onResult]. The whole one-shot platform fetch lives in `OneShotLocationProvider`; this only adds
 * the permission prompt + coroutine plumbing.
 */
@Composable
fun rememberCurrentGps(onResult: (GpsFixResult) -> Unit): CurrentGpsLauncher {
    val service = koinInject<LocationService>()
    val scope = rememberCoroutineScope()
    val onResultState = rememberUpdatedState(onResult)

    val permissionsManager = createPermissionsManager { type, status, _ ->
        if (type != PermissionType.LOCATION) return@createPermissionsManager
        if (status == PermissionStatus.GRANTED) {
            scope.launch { onResultState.value(service.requestLatestGps(GpsRequestReason.LiveMap)) }
        } else {
            onResultState.value(GpsFixResult.PermissionDenied)
        }
    }

    return remember(service) {
        CurrentGpsLauncher {
            scope.launch {
                if (permissionsManager.isPermissionGranted(PermissionType.LOCATION)) {
                    onResultState.value(service.requestLatestGps(GpsRequestReason.LiveMap))
                } else {
                    permissionsManager.askPermission(PermissionType.LOCATION)
                }
            }
        }
    }
}
