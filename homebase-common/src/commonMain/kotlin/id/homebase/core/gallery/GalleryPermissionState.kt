package id.homebase.core.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.PermissionsManager
import id.homebase.core.permissions.createPermissionsManager

@Composable
fun rememberGalleryPermissionState(
    onGalleryPermissionGranted: () -> Unit,
): GalleryPermissionState {
    var hasGalleryPermission by remember { mutableStateOf(false) }
    var hasPartialGalleryPermission by remember { mutableStateOf(false) }
    var hasPermanentlyDeniedGalleryPermission by remember { mutableStateOf(false) }
    var hasPermanentlyDeniedPartialGalleryPermission by remember { mutableStateOf(false) }

    val permissionsHandler =
        createPermissionsManager { type, status, denied ->
            when (type) {
                PermissionType.GALLERY -> {
                    hasGalleryPermission = status == PermissionStatus.GRANTED
                    if (hasGalleryPermission) {
                        hasPermanentlyDeniedGalleryPermission = false
                        onGalleryPermissionGranted()
                    } else {
                        hasPermanentlyDeniedGalleryPermission = denied
                    }
                }

                PermissionType.GALLERY_LIMITED -> {
                    hasPartialGalleryPermission = status == PermissionStatus.GRANTED
                    if (hasPartialGalleryPermission) {
                        onGalleryPermissionGranted()
                        hasPermanentlyDeniedPartialGalleryPermission = false
                    } else {
                        hasPermanentlyDeniedPartialGalleryPermission = denied
                    }
                }

                else -> {}
            }
        }


    LaunchedEffect(Unit) {
        hasGalleryPermission = permissionsHandler.isPermissionGranted(PermissionType.GALLERY)
        hasPartialGalleryPermission =
            permissionsHandler.isPermissionGranted(PermissionType.GALLERY_LIMITED)

        if (hasGalleryPermission || hasPartialGalleryPermission) {
            onGalleryPermissionGranted()
        }
    }

    return remember(hasGalleryPermission, hasPartialGalleryPermission, hasPermanentlyDeniedGalleryPermission, hasPermanentlyDeniedPartialGalleryPermission) {
        GalleryPermissionState(
            hasGalleryPermission = hasGalleryPermission,
            hasPartialGalleryPermission = hasPartialGalleryPermission,
            hasPermanentlyDeniedPermission = hasPermanentlyDeniedGalleryPermission,
            hasPermanentlyDeniedPartialPermission = hasPermanentlyDeniedPartialGalleryPermission,
            permissionsManager = permissionsHandler,
        )
    }
}

class GalleryPermissionState(
    val hasGalleryPermission: Boolean,
    val hasPartialGalleryPermission: Boolean,
    val hasPermanentlyDeniedPermission: Boolean,
    val hasPermanentlyDeniedPartialPermission: Boolean,
    private val permissionsManager: PermissionsManager,
) {
    fun requestGalleryPermission() {
        if (hasPermanentlyDeniedPermission) {
            permissionsManager.launchSettings()
            return
        }
        permissionsManager.askPermission(PermissionType.GALLERY)
    }

    fun requestPartialGalleryPermission() {
        if (hasPermanentlyDeniedPartialPermission) {
            permissionsManager.launchSettings()
            return
        }
        permissionsManager.askPermission(PermissionType.GALLERY_LIMITED)
    }

    fun launchSettings() {
        permissionsManager.launchSettings()
    }
}
