package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

internal expect fun cameraDialogProperties(): DialogProperties

@Composable
internal expect fun CameraWindowEffect()
