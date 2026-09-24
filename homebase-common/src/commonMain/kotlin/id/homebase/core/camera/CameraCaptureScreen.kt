package id.homebase.core.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import id.homebase.core.haptics.rememberHaptics
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.camera_permission_body
import id.homebase.resources.camera_permission_denied_body
import id.homebase.resources.camera_permission_denied_title
import id.homebase.resources.camera_permission_open_settings
import id.homebase.resources.camera_permission_retry
import id.homebase.resources.camera_permission_settings_body
import id.homebase.resources.camera_permission_title
import id.homebase.resources.camera_unavailable_body
import id.homebase.resources.camera_unavailable_title
import id.homebase.resources.close
import io.github.vinceglb.filekit.PlatformFile
import org.jetbrains.compose.resources.stringResource

internal const val PERMISSION_PANE_TAG = "camera_permission_pane"
internal const val PERMISSION_ACTION_TAG = "camera_permission_action"
internal const val UNAVAILABLE_TAG = "camera_unavailable"

/** A full-screen camera in its own window, so it stacks above sheets and dialogs that launch it. */
@Composable
fun CameraCaptureDialog(
    allowedModes: CameraModes,
    initialMode: CaptureMode = CaptureMode.Photo,
    mirrorFront: Boolean = true,
    onResult: (PlatformFile) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = cameraDialogProperties()) {
        CameraWindowEffect()
        CameraCaptureScreen(
            allowedModes = allowedModes,
            initialMode = initialMode,
            mirrorFront = mirrorFront,
            onResult = onResult,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun CameraCaptureScreen(
    allowedModes: CameraModes,
    initialMode: CaptureMode = CaptureMode.Photo,
    mirrorFront: Boolean = true,
    onResult: (PlatformFile) -> Unit,
    onDismiss: () -> Unit,
) {
    HomebaseTheme(darkTheme = true, followsSystemTheme = false, updatesSystemChrome = false) {
        val permissions = rememberCameraPermissions()
        CompositionLocalProvider(LocalReduceMotion provides rememberReduceMotion()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
                if (permissions.camera == CameraPermissionState.Granted) {
                    // Created only once granted: binding without the permission fails instead of waiting.
                    val engine = rememberCameraEngine()
                    CameraCaptureContent(
                        engine = engine,
                        allowedModes = allowedModes,
                        initialMode = initialMode,
                        mirrorFront = mirrorFront,
                        mic = permissions.mic,
                        onRequestMic = permissions::requestMic,
                        haptics = rememberHaptics(),
                        deviceRotation = rememberDeviceRotation(),
                        displayRotation = rememberDisplayRotation(),
                        onResult = onResult,
                        onDismiss = onDismiss,
                    )
                } else {
                    CameraPermissionPane(
                        state = permissions.camera,
                        onAction = permissions::retryCamera,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CameraPermissionPane(
    state: CameraPermissionState,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(PERMISSION_PANE_TAG),
    ) {
        CameraCloseButton(onClick = onDismiss, iconRotation = { 0f }, modifier = Modifier.padding(8.dp))
        if (state == CameraPermissionState.Checking) {
            StartingIndicator(visible = true, modifier = Modifier.align(Alignment.Center), delayMs = 300)
            return@Box
        }
        val (title, body) = when (state) {
            CameraPermissionState.Denied ->
                stringResource(MR.string.camera_permission_denied_title) to
                    stringResource(MR.string.camera_permission_denied_body)

            CameraPermissionState.PermanentlyDenied ->
                stringResource(MR.string.camera_permission_denied_title) to
                    stringResource(MR.string.camera_permission_settings_body)

            else -> stringResource(MR.string.camera_permission_title) to
                stringResource(MR.string.camera_permission_body)
        }
        val action = when (state) {
            CameraPermissionState.Denied -> stringResource(MR.string.camera_permission_retry)
            CameraPermissionState.PermanentlyDenied -> stringResource(MR.string.camera_permission_open_settings)
            else -> null
        }
        CameraMessage(
            icon = Icons.Outlined.PhotoCamera,
            title = title,
            body = body,
            modifier = Modifier.align(Alignment.Center),
        ) {
            if (action != null) {
                Button(onClick = onAction, modifier = Modifier.testTag(PERMISSION_ACTION_TAG)) { Text(action) }
            }
        }
    }
}

@Composable
internal fun CameraUnavailablePane(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(UNAVAILABLE_TAG),
    ) {
        CameraMessage(
            icon = Icons.Outlined.NoPhotography,
            title = stringResource(MR.string.camera_unavailable_title),
            body = stringResource(MR.string.camera_unavailable_body),
            modifier = Modifier.align(Alignment.Center),
        ) {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(MR.string.close)) }
        }
    }
}

@Composable
private fun CameraMessage(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.widthIn(max = 360.dp).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        action()
    }
}

