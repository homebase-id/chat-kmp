package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * Dialog properties for the full-screen camera dialog: not dismissed by an outside click, not limited to the
 * platform default width, drawn edge to edge. Same on every platform; only the insets and scrim handling differ.
 */
internal expect fun cameraDialogProperties(): DialogProperties

/**
 * Applies camera-screen window chrome while composed and restores it on leaving: no dim, no window animation
 * and no contrast scrim on Android; portrait lock on iPhone (iPad keeps rotating) on iOS.
 * Does nothing on desktop and web, which have no such window chrome.
 */
@Composable
internal expect fun CameraWindowEffect()
