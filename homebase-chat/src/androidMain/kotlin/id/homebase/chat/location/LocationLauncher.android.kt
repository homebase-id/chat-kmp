package id.homebase.chat.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

private const val TAG = "LocationLauncher.android"

@Composable
actual fun rememberCurrentLocationLauncher(
    onResult: (LocationResult) -> Unit,
): LocationLauncher {
    val context = LocalContext.current
    val onResultState = rememberUpdatedState(onResult)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        Logger.d(tag = TAG) { "permission result granted=$granted (raw=$result)" }
        if (granted) {
            fetchLocation(context, onResultState.value)
        } else {
            onResultState.value(LocationResult.PermissionDenied)
        }
    }

    return remember(context) {
        object : LocationLauncher {
            override fun launch() {
                if (hasLocationPermission(context)) {
                    Logger.d(tag = TAG) { "launch: permission already granted, fetching" }
                    fetchLocation(context, onResultState.value)
                } else {
                    Logger.d(tag = TAG) { "launch: requesting permission" }
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission") // Guarded by hasLocationPermission() at every call site.
private fun fetchLocation(
    context: Context,
    onResult: (LocationResult) -> Unit,
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val cts = CancellationTokenSource()
    Logger.d(tag = TAG) { "fetchLocation: requesting fused getCurrentLocation (BALANCED_POWER)" }
    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
        .addOnSuccessListener { location ->
            if (location == null) {
                // Common on emulators with no mock location set, indoors with no last fix, or
                // when location services are off at the OS level. Fused-API doesn't differentiate.
                Logger.w(tag = TAG) {
                    "fused getCurrentLocation returned null (no mock location? location services off?)"
                }
                onResult(LocationResult.Unavailable)
            } else {
                Logger.d(tag = TAG) {
                    "fused getCurrentLocation success lat=${location.latitude} lon=${location.longitude}"
                }
                onResult(
                    LocationResult.Success(
                        LocationFix(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        )
                    )
                )
            }
        }
        .addOnFailureListener { e ->
            Logger.w(throwable = e, tag = TAG) { "fused getCurrentLocation failed" }
            onResult(LocationResult.Unavailable)
        }
}
