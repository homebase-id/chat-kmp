package id.homebase.core.permissions

import androidx.compose.runtime.Composable

class PermissionsManager : PermissionHandler {
    @Composable
    override fun askPermission(permission: PermissionType) {

    }

    @Composable
    override fun isPermissionGranted(permission: PermissionType): Boolean {
        return true
    }

    @Composable
    override fun launchSettings() {

    }
}

@Composable
actual fun createPermissionsManager(callback: PermissionCallback): PermissionHandler {
    return PermissionsManager(callback)
}