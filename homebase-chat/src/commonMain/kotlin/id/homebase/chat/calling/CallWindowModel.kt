package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import id.homebase.api.browser.WindowKeys
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallPhase
import id.homebase.resources.MR
import id.homebase.resources.call_pill_text
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class CallWindowCloseAction { Decline, Hangup, ConfirmEnd, Dismiss }

fun callWindowCloseAction(phase: CallPhase): CallWindowCloseAction = when (phase) {
    CallPhase.Incoming -> CallWindowCloseAction.Decline
    CallPhase.Outgoing, CallPhase.Connecting -> CallWindowCloseAction.Hangup
    CallPhase.Connected, CallPhase.Reconnecting -> CallWindowCloseAction.ConfirmEnd
    CallPhase.Idle, is CallPhase.Ended -> CallWindowCloseAction.Dismiss
}

fun callWindowVisible(ui: CallUiState): Boolean = ui.visible

fun callWindowElapsed(ui: CallUiState, nowMs: Long): String? {
    val connectedAt = ui.connectedAtMs
    if (connectedAt == null || !ui.inProgress) return null
    return formatCallDuration(((nowMs - connectedAt) / 1000).coerceAtLeast(0))
}

/** One call UI state shared by the desktop call window and the main window's ongoing-call bar. */
class CallWindowModel(
    val viewModel: CallViewModel,
    private val focus: (key: String) -> Unit,
    private val displayName: suspend (OdinId) -> String? = { null },
) {
    val uiState: StateFlow<CallUiState> get() = viewModel.uiState

    val windowVisible: StateFlow<Boolean> =
        viewModel.uiState.map(::callWindowVisible).stateIn(viewModel.viewModelScope, SharingStarted.Eagerly, false)

    val windowKey: StateFlow<String?> =
        viewModel.uiState.map { it.callId }.stateIn(viewModel.viewModelScope, SharingStarted.Eagerly, null)

    val ringing: StateFlow<Boolean> =
        viewModel.uiState.map { it.phase == CallPhase.Incoming }.stateIn(viewModel.viewModelScope, SharingStarted.Eagerly, false)

    fun isVideoCall(): Boolean = viewModel.uiState.value.isVideoCall

    @Composable
    fun peerName(odinId: String?): String = if (odinId == null) "" else rememberPeerName(odinId, displayName)

    fun requestClose() = viewModel.requestClose()

    fun focusCallWindow() = focus(WindowKeys.CALL)
}

@Composable
fun CallWindowContent(model: CallWindowModel, onMinimize: () -> Unit, topInset: Dp, modifier: Modifier = Modifier) {
    val ui by model.uiState.collectAsStateWithLifecycle()
    val name = model.peerName(ui.peer)
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxSize().padding(top = topInset)) {
            CallOverlay(
                viewModel = model.viewModel,
                ui = ui,
                peerName = name,
                avatar = { size -> ui.peer?.let { PeerAvatar(it, name, size) } },
                onMinimize = onMinimize,
            )
        }
    }
}

@Composable
fun rememberCallWindowTitle(model: CallWindowModel): String {
    val ui by model.uiState.collectAsStateWithLifecycle()
    val name = model.peerName(ui.peer)
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(ui.connectedAtMs) {
        while (ui.connectedAtMs != null) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    val elapsed = callWindowElapsed(ui, now) ?: return name
    return stringResource(MR.string.call_pill_text, name, elapsed)
}

@Composable
fun OngoingCallBarHost(
    model: CallWindowModel,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    peerName: @Composable (String?) -> String = model::peerName,
) {
    val ui by model.uiState.collectAsStateWithLifecycle()
    if (!ui.inProgress) return
    val name = peerName(ui.peer)
    Box(modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(top = topInset)) {
        OngoingCallBar(ui = ui, peerName = name, onClick = model::focusCallWindow)
    }
}

/** Lays the app content out under the desktop ongoing-call bar, or hosts the call overlay where the platform has no call window. */
@Composable
fun CallChrome(
    hostCallOverlay: Boolean,
    callWindow: CallWindowModel?,
    topInset: Dp,
    callHost: @Composable () -> Unit,
    content: @Composable (topInset: Dp) -> Unit,
) {
    val barShown = !hostCallOverlay && callWindow != null && callWindow.uiState.collectAsStateWithLifecycle().value.inProgress
    Column(Modifier.fillMaxSize()) {
        if (!hostCallOverlay && callWindow != null) OngoingCallBarHost(callWindow, topInset = topInset)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content(if (barShown) 0.dp else topInset)
            if (hostCallOverlay) callHost()
        }
    }
}
