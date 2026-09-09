package id.homebase.core.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import id.homebase.core.notifications.WebPushCapability
import id.homebase.core.notifications.webPushBridge
import kotlinx.coroutines.launch

@Composable
actual fun createPermissionsManager(
    onPermissionResult: (PermissionType, PermissionStatus, Boolean) -> Unit
): PermissionsManager {
    val scope = rememberCoroutineScope()
    return remember(scope, onPermissionResult) {
        object : PermissionsManager {
            private val bridge = webPushBridge()

            /** Must be reached from a user gesture — every browser gates the prompt on one. */
            override fun askPermission(permission: PermissionType) {
                val bridge = bridge
                if (permission != PermissionType.NOTIFICATION || bridge == null) return
                scope.launch {
                    val capability = bridge.requestPermission()
                    onPermissionResult(
                        permission,
                        if (capability == WebPushCapability.GRANTED) PermissionStatus.GRANTED
                        else PermissionStatus.DENIED,
                        // A browser denial is unreachable from the page — only the browser's own
                        // site settings can undo it, which is why launchSettings() stays a no-op.
                        capability == WebPushCapability.DENIED,
                    )
                }
            }

            override suspend fun isPermissionGranted(permission: PermissionType): Boolean =
                webPermissionGranted(
                    permission,
                    notificationGranted = bridge?.capability() == WebPushCapability.GRANTED,
                )

            override fun launchSettings() {}
        }
    }
}
