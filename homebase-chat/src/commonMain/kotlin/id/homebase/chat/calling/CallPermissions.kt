package id.homebase.chat.calling

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.PermissionsManager
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.resources.MR
import id.homebase.resources.call_camera_denied_body
import id.homebase.resources.call_camera_denied_title
import id.homebase.resources.call_open_settings
import id.homebase.resources.call_permission_body
import id.homebase.resources.call_permission_title
import id.homebase.resources.call_verification_ok
import id.homebase.resources.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

class CallPermissionGate internal constructor(
    private val request: (video: Boolean, onGranted: (video: Boolean) -> Unit) -> Unit,
) {
    fun run(video: Boolean, onGranted: (video: Boolean) -> Unit) = request(video, onGranted)
}

@Composable
fun rememberCallPermissionGate(): CallPermissionGate {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    val manager: PermissionsManager = createPermissionsManager { _, status, _ ->
        pending?.complete(status == PermissionStatus.GRANTED)
    }
    val managerRef by rememberUpdatedState(manager)

    suspend fun ensure(type: PermissionType): Boolean {
        if (managerRef.isPermissionGranted(type)) return true
        val answer = CompletableDeferred<Boolean>().also { pending = it }
        managerRef.askPermission(type)
        return answer.await()
    }

    val gate = remember {
        CallPermissionGate { video, onGranted ->
            scope.launch {
                if (!ensure(PermissionType.RECORD_AUDIO)) {
                    blocked = true
                    return@launch
                }
                val withVideo = video && ensure(PermissionType.CAMERA)
                if (video && !withVideo) cameraDenied = true
                onGranted(withVideo)
            }
        }
    }

    if (blocked) {
        AlertDialog(
            onDismissRequest = { blocked = false },
            title = { Text(stringResource(MR.string.call_permission_title)) },
            text = { Text(stringResource(MR.string.call_permission_body)) },
            confirmButton = {
                TextButton(onClick = { blocked = false; managerRef.launchSettings() }) {
                    Text(stringResource(MR.string.call_open_settings))
                }
            },
            dismissButton = { TextButton(onClick = { blocked = false }) { Text(stringResource(MR.string.cancel)) } },
        )
    }
    if (cameraDenied) {
        AlertDialog(
            onDismissRequest = { cameraDenied = false },
            title = { Text(stringResource(MR.string.call_camera_denied_title)) },
            text = { Text(stringResource(MR.string.call_camera_denied_body)) },
            confirmButton = { TextButton(onClick = { cameraDenied = false }) { Text(stringResource(MR.string.call_verification_ok)) } },
            dismissButton = {
                TextButton(onClick = { cameraDenied = false; managerRef.launchSettings() }) {
                    Text(stringResource(MR.string.call_open_settings))
                }
            },
        )
    }
    return gate
}
