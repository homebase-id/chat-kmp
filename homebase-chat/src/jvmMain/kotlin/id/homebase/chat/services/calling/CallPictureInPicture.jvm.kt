package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable

@Composable
actual fun rememberInPictureInPicture(): Boolean = false

@Composable
actual fun CallPictureInPictureEffect(eligible: Boolean, remote: VideoHandle?, muted: Boolean, onToggleMute: () -> Unit, onHangup: () -> Unit) = Unit
