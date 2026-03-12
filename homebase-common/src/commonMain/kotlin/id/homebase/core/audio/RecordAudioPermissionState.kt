package id.homebase.core.audio

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
fun rememberRecordAudioPermissionState(
    onPermissionGranted: () -> Unit,
): RecordAudioPermissionState {
    var hasPermission by remember { mutableStateOf(false) }
    var hasPermanentlyDeniedPermission by remember { mutableStateOf(false) }

    val permissionsHandler =
        createPermissionsManager { type, status, denied ->
            when (type) {
                PermissionType.RECORD_AUDIO -> {
                    hasPermission = status == PermissionStatus.GRANTED
                    if (hasPermission) {
                        hasPermanentlyDeniedPermission = false
                        onPermissionGranted()
                    } else {
                        hasPermanentlyDeniedPermission = denied
                    }
                }

                else -> {}
            }
        }


    LaunchedEffect(Unit) {
        hasPermission = permissionsHandler.isPermissionGranted(PermissionType.RECORD_AUDIO)

        if (hasPermission) {
            onPermissionGranted()
        }
    }

    return remember(hasPermission, hasPermanentlyDeniedPermission) {
        RecordAudioPermissionState(
            hasPermission = hasPermission,
            hasPermanentlyDeniedPermission = hasPermanentlyDeniedPermission,
            permissionsManager = permissionsHandler,
        )
    }
}

class RecordAudioPermissionState(
    val hasPermission: Boolean,
    val hasPermanentlyDeniedPermission: Boolean,
    private val permissionsManager: PermissionsManager,
) {
    fun requestPermission() {
        if (hasPermanentlyDeniedPermission) {
            permissionsManager.launchSettings()
            return
        }
        permissionsManager.askPermission(PermissionType.RECORD_AUDIO)
    }

    fun launchSettings() {
        permissionsManager.launchSettings()
    }
}
