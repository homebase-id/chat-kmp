package id.homebase.core.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

@Composable
actual fun createPermissionsManager(
    onPermissionResult: (PermissionType, PermissionStatus, Boolean) -> Unit
): PermissionsManager {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val genericPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (key, value) ->
            var isPermanentlyDenied = false
            if (!value && activity != null) {
                isPermanentlyDenied =
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, key)
            }

            println(
                "Permission: $key status: $value (isPermanentlyDenied: $isPermanentlyDenied)"
            )

            key.toPermissionType()?.let {
                onPermissionResult(
                    it,
                    if (value) PermissionStatus.GRANTED else PermissionStatus.DENIED,
                    isPermanentlyDenied
                )
            }
        }
    }

    return AndroidPermissionsManager(context, genericPermissionLauncher)
}

private fun String.toPermissionType(): PermissionType? {
    if (this == Manifest.permission.CAMERA) return PermissionType.CAMERA

    if (this == Manifest.permission.RECORD_AUDIO) return PermissionType.RECORD_AUDIO

    if (this == Manifest.permission.READ_MEDIA_IMAGES || this == Manifest.permission.READ_EXTERNAL_STORAGE) return PermissionType.GALLERY

    if (this == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) return PermissionType.GALLERY_LIMITED

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && this == Manifest.permission.POST_NOTIFICATIONS) return PermissionType.NOTIFICATION

    if (this == Manifest.permission.ACCESS_FINE_LOCATION || this == Manifest.permission.ACCESS_COARSE_LOCATION) return PermissionType.LOCATION

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this == Manifest.permission.ACCESS_BACKGROUND_LOCATION) return PermissionType.LOCATION_ALWAYS

    if (this == Manifest.permission.READ_CONTACTS) return PermissionType.CONTACTS

    return null
}

class AndroidPermissionsManager(
    val context: Context, val genericPermissionLauncher: ActivityResultLauncher<Array<String>>
) : PermissionsManager {
    override fun askPermission(permission: PermissionType) {
        when (permission) {
            PermissionType.CAMERA -> {
                genericPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }

            PermissionType.RECORD_AUDIO -> {
                genericPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }

            PermissionType.GALLERY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    genericPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                        )
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    genericPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                        )
                    )
                } else {
                    genericPermissionLauncher.launch(
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    )
                }
            }

            PermissionType.GALLERY_LIMITED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    genericPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                        )
                    )
                }
            }

            PermissionType.NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    genericPermissionLauncher.launch(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                    )
                }
            }

            PermissionType.LOCATION -> {
                genericPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }

            PermissionType.LOCATION_ALWAYS -> {
                // Background location is a separate runtime permission from API 29 and
                // may only be requested once foreground location is already granted —
                // callers gate the request on isPermissionGranted(LOCATION).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    genericPermissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    )
                }
            }

            PermissionType.CONTACTS -> {
                genericPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            }
        }
    }

    override suspend fun isPermissionGranted(permission: PermissionType): Boolean {
        return when (permission) {
            PermissionType.CAMERA -> {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }

            PermissionType.RECORD_AUDIO -> {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            }

            PermissionType.GALLERY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val hasImagePermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_MEDIA_IMAGES
                    ) == PackageManager.PERMISSION_GRANTED

                    val hasVideoPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_MEDIA_VIDEO
                    ) == PackageManager.PERMISSION_GRANTED

                    hasImagePermission && hasVideoPermission  // ← Check both
                } else {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }

            PermissionType.GALLERY_LIMITED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    false
                }
            }

            PermissionType.NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
            }

            PermissionType.LOCATION -> {
                // Coarse-only ("approximate") still counts as while-in-use access.
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
            }

            PermissionType.LOCATION_ALWAYS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    isPermissionGranted(PermissionType.LOCATION)
                }
            }

            PermissionType.CONTACTS -> {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    override fun launchSettings() {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).also { context.startActivity(it) }
    }
}
