package id.homebase.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * App-context-backed location permission read. Needs only a [Context]
 * (`ContextCompat.checkSelfPermission`), not the Compose `ActivityResult`
 * launcher that [createPermissionsManager] sets up for *requesting* — so it is
 * safe to construct from Koin's `platformModule()` with `androidContext()`.
 *
 * Mirrors `AndroidPermissionsManager.isPermissionGranted(PermissionType.LOCATION)`:
 * coarse-only ("approximate") still counts as while-in-use access.
 */
fun androidLocationPermissionQuery(context: Context): LocationPermissionQuery =
    LocationPermissionQuery {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }
