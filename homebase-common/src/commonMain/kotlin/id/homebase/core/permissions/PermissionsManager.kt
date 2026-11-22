package id.homebase.core.permissions

import androidx.compose.runtime.Composable

@Composable
expect fun createPermissionsManager(
    onPermissionResult: (PermissionType, PermissionStatus, Boolean) -> Unit
): PermissionsManager

/**
 * The system permissions the app asks for, through the same calls on every platform.
 *
 * - [askPermission] shows the platform's own prompt, and reports through the `onPermissionResult` given to
 *   [createPermissionsManager]: the permission, its status, and whether it is denied for good (the system won't prompt
 *   again, so only its settings can change it). Android and iOS ask for every [PermissionType]. Desktop (macOS) and web ask
 *   only for [PermissionType.NOTIFICATION] and ignore the rest; on web it must run from a user gesture.
 * - [isPermissionGranted] answers without prompting. On desktop a permission other than notifications counts as granted; on
 *   web too, except recording audio, which has no browser recorder in this build.
 * - [launchSettings] opens where the user can change it: the app's settings page on Android and iOS, notification settings
 *   on desktop. Web does nothing: only the browser's own site settings undo a denial.
 */
interface PermissionsManager {
    fun askPermission(permission: PermissionType)
    suspend fun isPermissionGranted(permission: PermissionType): Boolean
    fun launchSettings()
}
