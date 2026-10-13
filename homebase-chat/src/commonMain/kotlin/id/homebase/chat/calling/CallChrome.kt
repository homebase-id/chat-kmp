package id.homebase.chat.calling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.calling.CallPhase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow

internal const val ChromeIdleMs = 3_000L

internal fun chromeAutoHideEligible(ui: CallUiState, large: Boolean, held: Boolean, screenReader: Boolean): Boolean =
    !large && !held && !screenReader && ui.isVideoCall && ui.phase == CallPhase.Connected

/** The dock, top bar and name pill of a phone video call: they fade after [ChromeIdleMs] without a touch, and a tap on the video toggles them. */
@Stable
internal class CallChrome {
    var visible by mutableStateOf(true)
        private set

    var holds by mutableIntStateOf(0)
    private var eligible = false
    private val touches = Channel<Unit>(Channel.CONFLATED)

    fun onTouch() {
        touches.trySend(Unit)
    }

    fun onTapVideo() {
        visible = !eligible || !visible
        onTouch()
    }

    suspend fun run(eligible: Boolean) {
        this.eligible = eligible
        visible = true
        if (!eligible) return
        touches.tryReceive()
        touches.receiveAsFlow().onStart { emit(Unit) }.collectLatest {
            delay(ChromeIdleMs)
            visible = false
        }
    }
}

internal val LocalCallChrome = staticCompositionLocalOf<CallChrome?> { null }

@Composable
internal fun HoldCallChrome(active: Boolean) {
    val chrome = LocalCallChrome.current ?: return
    DisposableEffect(chrome, active) {
        if (active) chrome.holds++
        onDispose { if (active) chrome.holds-- }
    }
}

internal class PreviewReserve(val top: Dp, val bottom: Dp)

internal val PreviewMargin = 16.dp

/** Space the self-view keeps clear at the top and bottom of the safe area: the bars when they show, only a margin when they are hidden. */
internal fun previewReserve(chromeVisible: Boolean, large: Boolean, compact: Boolean): PreviewReserve = when {
    !chromeVisible -> PreviewReserve(PreviewMargin, PreviewMargin)
    large -> PreviewReserve(80.dp, 128.dp)
    compact -> PreviewReserve(72.dp, 112.dp)
    else -> PreviewReserve(84.dp, 236.dp)
}
