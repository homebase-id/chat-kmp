package id.homebase.core.permissions

import androidx.compose.runtime.Composable
import id.homebase.core.notifications.MacNotificationAuthorization
import id.homebase.core.notifications.rememberOpenSystemNotificationSettings

@Composable
actual fun createPermissionsManager(
    onPermissionResult: (PermissionType, PermissionStatus, Boolean) -> Unit
): PermissionsManager {
    val openNotificationSettings = rememberOpenSystemNotificationSettings()
    return object : PermissionsManager {
        override fun askPermission(permission: PermissionType) {
            if (permission != PermissionType.NOTIFICATION) return
            // macOS never re-prompts once the user has answered, so a refusal is permanent.
            MacNotificationAuthorization.request { granted ->
                onPermissionResult(
                    permission,
                    if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
                    !granted,
                )
            }
        }

        override suspend fun isPermissionGranted(permission: PermissionType): Boolean =
            permission != PermissionType.NOTIFICATION || MacNotificationAuthorization.isGranted()

        override fun launchSettings() = openNotificationSettings()
    }
}
