package id.homebase.core.permissions

import androidx.compose.runtime.Composable

@Composable
expect fun createPermissionsManager(onPermissionResult: (PermissionType, PermissionStatus, Boolean) -> Unit): PermissionsManager

interface PermissionsManager {
    fun askPermission(permission: PermissionType)
    fun isPermissionGranted(permission: PermissionType): Boolean
    fun launchSettings()
}