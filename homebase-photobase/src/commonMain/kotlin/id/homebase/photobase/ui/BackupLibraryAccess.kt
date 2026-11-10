package id.homebase.photobase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager

/** Asks for the photo library, then for the original-location grant; a permanent "no" opens system settings. */
@Composable
fun rememberBackupLibraryAccess(onChanged: () -> Unit): () -> Unit {
    var denied by remember { mutableStateOf(false) }
    var manager by remember { mutableStateOf<id.homebase.core.permissions.PermissionsManager?>(null) }
    val permissions = createPermissionsManager { type, status, permanentlyDenied ->
        if (type == PermissionType.GALLERY) {
            denied = permanentlyDenied && status != PermissionStatus.GRANTED
            if (status == PermissionStatus.GRANTED) manager?.askPermission(PermissionType.MEDIA_LOCATION)
        }
        onChanged()
    }
    manager = permissions
    return {
        if (denied) permissions.launchSettings() else permissions.askPermission(PermissionType.GALLERY)
    }
}

/** Asks for Android's original-location grant; a permanent "no" opens system settings. */
@Composable
fun rememberPhotoLocationAccess(onChanged: () -> Unit): () -> Unit {
    var denied by remember { mutableStateOf(false) }
    val permissions = createPermissionsManager { type, status, permanentlyDenied ->
        if (type == PermissionType.MEDIA_LOCATION) denied = permanentlyDenied && status != PermissionStatus.GRANTED
        onChanged()
    }
    return {
        if (denied) permissions.launchSettings() else permissions.askPermission(PermissionType.MEDIA_LOCATION)
    }
}
