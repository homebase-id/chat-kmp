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
actual fun createPermissionsManager(onPermissionResult: (PermissionType, PermissionStatus, Boolean) -> Unit): PermissionsManager {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val genericPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (key, value) ->
            var isPermanentlyDenied = false
            if (!value && activity != null) {
                isPermanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(activity, key)
            }

            println("Permission: $key status: $value (isPermanentlyDenied: $isPermanentlyDenied)")

            key.toPermissionType()?.let {
                onPermissionResult(it, if (value) PermissionStatus.GRANTED else PermissionStatus.DENIED, isPermanentlyDenied)
            }
        }
    }

    return AndroidPermissionsManager(context, genericPermissionLauncher)
}

private fun String.toPermissionType(): PermissionType? {
    if (this == Manifest.permission.CAMERA)
        return PermissionType.CAMERA

    if (this == Manifest.permission.READ_MEDIA_IMAGES || this == Manifest.permission.READ_EXTERNAL_STORAGE)
        return PermissionType.GALLERY

    if (this == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return PermissionType.GALLERY_LIMITED

    return null
}

class AndroidPermissionsManager(
    val context: Context,
    val genericPermissionLauncher: ActivityResultLauncher<Array<String>>
) : PermissionsManager {
    override fun askPermission(permission: PermissionType) {
        when (permission) {
            PermissionType.CAMERA -> {
                genericPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }

            PermissionType.GALLERY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    genericPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                        )
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    genericPermissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
                } else {
                    genericPermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                }
            }

            PermissionType.GALLERY_LIMITED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    genericPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                        )
                    )
                }
            }
        }
    }

    override fun isPermissionGranted(permission: PermissionType): Boolean {
        return when (permission) {
            PermissionType.CAMERA -> {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }

            PermissionType.GALLERY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_MEDIA_IMAGES
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }

            PermissionType.GALLERY_LIMITED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    false
                }
            }
        }
    }

    override fun launchSettings() {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).also {
            context.startActivity(it)
        }
    }
}
