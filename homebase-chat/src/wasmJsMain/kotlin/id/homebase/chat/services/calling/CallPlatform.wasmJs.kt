package id.homebase.chat.services.calling

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

actual fun platformCallEngineFactory(): CallEngineFactory? = null

@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier, cornerRadius: Dp) = Unit

actual fun platformAudioRoutes(): List<AudioRoute> = emptyList()
