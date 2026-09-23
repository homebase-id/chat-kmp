package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

internal expect fun cameraDialogProperties(): DialogProperties

/** Dark system bars, full-bleed window and a portrait-locked HUD for the camera window. */
@Composable
internal expect fun CameraWindowEffect()

@Composable
internal expect fun KeepScreenOnEffect(enabled: Boolean)
