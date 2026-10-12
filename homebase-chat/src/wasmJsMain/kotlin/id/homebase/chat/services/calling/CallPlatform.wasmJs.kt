package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

actual fun platformCallEngineFactory(): CallEngineFactory? = null

@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier) = Unit

@Composable
actual fun CallRingEffect(ringing: Boolean, callerName: String) = Unit
