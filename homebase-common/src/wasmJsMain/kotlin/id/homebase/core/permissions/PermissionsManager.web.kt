package id.homebase.core.permissions

import androidx.compose.runtime.Composable

@Composable
actual fun createPermissionsManager(
    onPermissionResult: (PermissionType, PermissionStatus, Boolean) -> Unit
): PermissionsManager {
    return object : PermissionsManager {
        override fun askPermission(permission: PermissionType) {}

        override suspend fun isPermissionGranted(permission: PermissionType): Boolean {
            return true
        }

        override fun launchSettings() {}
    }
}
