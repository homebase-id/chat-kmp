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
    onResult: (LocationFix?) -> Unit,
): LocationLauncher {
    val context = LocalContext.current
    val onResultState = rememberUpdatedState(onResult)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchLocation(context, onResultState.value)
        } else {
            onResultState.value(null)
        }
    }

    return remember(context) {
        object : LocationLauncher {
            override fun launch() {
                if (hasLocationPermission(context)) {
                    fetchLocation(context, onResultState.value)
                } else {
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
    onResult: (LocationFix?) -> Unit,
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val cts = CancellationTokenSource()
    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
        .addOnSuccessListener { location ->
            if (location == null) {
                onResult(null)
            } else {
                onResult(
                    LocationFix(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                    )
                )
            }
        }
        .addOnFailureListener { e ->
            Logger.w(throwable = e, tag = TAG) { "FusedLocation getCurrentLocation failed" }
            onResult(null)
        }
}
