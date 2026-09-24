package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.PermissionsManager
import id.homebase.core.permissions.createPermissionsManager
import kotlinx.coroutines.launch

enum class CameraPermissionState { Checking, Requesting, Granted, Denied, PermanentlyDenied }

sealed interface CameraPermissionEvent {
    /** A status read: on open and on every resume, so a grant made in Settings is picked up. */
    data class Checked(val granted: Boolean) : CameraPermissionEvent
    data class Answered(val granted: Boolean, val permanentlyDenied: Boolean) : CameraPermissionEvent
    data object Retry : CameraPermissionEvent
}

object CameraPermissionGate {
    fun reduce(state: CameraPermissionState, event: CameraPermissionEvent): CameraPermissionState = when (event) {
        is CameraPermissionEvent.Checked -> when {
            event.granted -> CameraPermissionState.Granted
            state == CameraPermissionState.Checking || state == CameraPermissionState.Granted ->
                CameraPermissionState.Requesting
            else -> state
        }

        is CameraPermissionEvent.Answered -> when {
            event.granted -> CameraPermissionState.Granted
            event.permanentlyDenied -> CameraPermissionState.PermanentlyDenied
            else -> CameraPermissionState.Denied
        }

        CameraPermissionEvent.Retry ->
            if (state == CameraPermissionState.Denied) CameraPermissionState.Requesting else state
    }
}

/** Microphone access for video; `granted == null` until it has been read. */
data class MicPermission(
    val granted: Boolean? = null,
    val askedThisSession: Boolean = false,
    val permanentlyDenied: Boolean = false,
) {
    val needsAsking: Boolean get() = granted == false && !askedThisSession
    val isDenied: Boolean get() = granted == false && askedThisSession
}

@Stable
class CameraPermissions internal constructor() {
    internal var manager: PermissionsManager? = null

    var camera by mutableStateOf(CameraPermissionState.Checking)
        internal set
    var mic by mutableStateOf(MicPermission())
        internal set

    internal fun onEvent(event: CameraPermissionEvent) {
        camera = CameraPermissionGate.reduce(camera, event)
    }

    internal fun onResult(type: PermissionType, granted: Boolean, permanentlyDenied: Boolean) {
        when (type) {
            PermissionType.CAMERA ->
                onEvent(CameraPermissionEvent.Answered(granted, permanentlyDenied && !granted))

            PermissionType.RECORD_AUDIO -> mic = mic.copy(
                granted = granted,
                askedThisSession = true,
                permanentlyDenied = permanentlyDenied && !granted,
            )

            else -> Unit
        }
    }

    fun retryCamera() {
        if (camera == CameraPermissionState.PermanentlyDenied) manager?.launchSettings()
        else onEvent(CameraPermissionEvent.Retry)
    }

    fun requestMic() {
        val manager = manager ?: return
        if (mic.permanentlyDenied) {
            manager.launchSettings()
            return
        }
        mic = mic.copy(askedThisSession = true)
        manager.askPermission(PermissionType.RECORD_AUDIO)
    }

    internal suspend fun refresh() {
        val manager = manager ?: return
        onEvent(CameraPermissionEvent.Checked(manager.isPermissionGranted(PermissionType.CAMERA)))
        val micGranted = manager.isPermissionGranted(PermissionType.RECORD_AUDIO)
        mic = mic.copy(granted = micGranted, permanentlyDenied = mic.permanentlyDenied && !micGranted)
    }
}

@Composable
fun rememberCameraPermissions(): CameraPermissions {
    val permissions = remember { CameraPermissions() }
    permissions.manager = createPermissionsManager { type, status, permanentlyDenied ->
        permissions.onResult(type, status == PermissionStatus.GRANTED, permanentlyDenied)
    }
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(permissions) {
        val job = scope.launch { permissions.refresh() }
        onPauseOrDispose { job.cancel() }
    }

    LaunchedEffect(permissions, permissions.camera) {
        if (permissions.camera == CameraPermissionState.Requesting) {
            permissions.manager?.askPermission(PermissionType.CAMERA)
        }
    }
    return permissions
}
