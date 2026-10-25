package id.homebase.chat.services.calling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

actual fun platformCallEngineFactory(): CallEngineFactory? = null

@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier, cornerRadius: Dp) = Unit

actual fun audioRouteChanges(): Flow<List<AudioRoute>> = flowOf(emptyList())

@Composable
actual fun rememberScreenReaderOn(): Boolean = false

actual class CallVideoRenderers actual constructor() {
    actual fun keepOnly(handles: List<VideoHandle>) = Unit
}
