package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import id.homebase.api.browser.WindowKeys
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.resources.MR
import id.homebase.resources.call_button_video
import id.homebase.resources.call_button_voice
import id.homebase.resources.call_pill_text
import id.homebase.resources.call_window_title
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
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

/** A ring the user never answered has nothing left to show once it ends; the ring window just goes away. */
internal fun CallUiState.unansweredRingEnded(): Boolean {
    val reason = endReason ?: return false
    return direction == CallDirection.Incoming && connectedAtMs == null &&
        (reason == CallEndReason.Missed || reason == CallEndReason.Cancelled || reason == CallEndReason.Declined)
}

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
        viewModel.uiState.map { it.visible && !it.unansweredRingEnded() }.stateIn(viewModel.viewModelScope, SharingStarted.Eagerly, false)

    val windowKey: StateFlow<String?> =
        viewModel.uiState.map { it.callId }.stateIn(viewModel.viewModelScope, SharingStarted.Eagerly, null)

    val live: StateFlow<Boolean> =
        viewModel.uiState.map(::callIsLive).stateIn(viewModel.viewModelScope, SharingStarted.Eagerly, false)

    val muted: StateFlow<Boolean> =
        viewModel.uiState.map { it.muted }.stateIn(viewModel.viewModelScope, SharingStarted.Eagerly, false)

    val canToggleCamera: StateFlow<Boolean> =
        viewModel.uiState.map(::callCameraToggleAvailable).stateIn(viewModel.viewModelScope, SharingStarted.Eagerly, false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val trayItems: StateFlow<List<CallTrayItem>> =
        viewModel.uiState.map { TrayKey(it.inProgress, it.peer, it.connectedAtMs, it.muted) }
            .distinctUntilChanged()
            .flatMapLatest { key ->
                if (!key.inProgress) return@flatMapLatest flowOf(emptyList())
                flow {
                    val name = key.peer?.let { lookupName(it) }.orEmpty()
                    while (true) {
                        val ui = viewModel.uiState.value
                        emit(callTrayItems(ui, name, callWindowElapsed(ui, Clock.System.now().toEpochMilliseconds())))
                        if (key.connectedAtMs == null) awaitCancellation()
                        delay(1_000)
                    }
                }
            }.stateIn(viewModel.viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private data class TrayKey(val inProgress: Boolean, val peer: String?, val connectedAtMs: Long?, val muted: Boolean)

    private suspend fun lookupName(peer: String): String =
        try {
            displayName(OdinId(peer))?.takeIf(String::isNotBlank)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: peer

    val shortcutGate = ShortcutGate()

    val ringing: StateFlow<Boolean> =
        viewModel.uiState.map { it.phase == CallPhase.Incoming }.stateIn(viewModel.viewModelScope, SharingStarted.Eagerly, false)

    fun isVideoCall(): Boolean = viewModel.uiState.value.isVideoCall

    @Composable
    fun peerName(odinId: String?): String = if (odinId == null) "" else rememberPeerName(odinId, displayName)

    fun requestClose() = viewModel.requestClose()

    fun acceptVoice() = viewModel.accept(withVideo = false)

    fun acceptVideo() = viewModel.accept(withVideo = true)

    fun answer() = if (isVideoCall()) acceptVideo() else acceptVoice()

    fun decline() = viewModel.decline()

    fun toggleMute() = viewModel.toggleMute()

    fun toggleVideo() = viewModel.toggleVideo()

    fun hangup() = viewModel.hangup()

    private fun gated(enabled: StateFlow<Boolean>, shortcut: CallShortcut, action: () -> Unit) {
        if (enabled.value && shortcutGate.accept(shortcut)) action()
    }

    fun menuToggleMute() = gated(live, CallShortcut.ToggleMute, ::toggleMute)

    fun menuToggleVideo() = gated(canToggleCamera, CallShortcut.ToggleCamera, ::toggleVideo)

    fun menuHangup() = gated(live, CallShortcut.HangUp, ::hangup)

    /** One set of call controls at a time: while the always-on-top mini window is up, the main window's bar stands down. */
    val miniWindowOpen = MutableStateFlow(false)

    fun menuToggleMini(toggle: () -> Unit) = gated(live, CallShortcut.ToggleMini, toggle)

    fun focusCallWindow() = focus(WindowKeys.CALL)
}

@Composable
fun CallWindowContent(
    model: CallWindowModel,
    onMinimize: () -> Unit,
    topInset: Dp,
    modifier: Modifier = Modifier,
    mini: Boolean = false,
    onToggleMini: (() -> Unit)? = null,
    macShortcuts: Boolean = false,
    onMiniDragStart: () -> Unit = {},
    onMiniDrag: () -> Unit = {},
) {
    val ui by model.uiState.collectAsStateWithLifecycle()
    val name = model.peerName(ui.peer)
    val avatar: @Composable (Dp) -> Unit = { size -> ui.peer?.let { PeerAvatar(it, name, size) } }
    CompositionLocalProvider(LocalMacShortcuts provides macShortcuts, LocalShortcutGate provides model.shortcutGate) {
        if (mini) {
            val events = rememberCallEvents(model.viewModel)
            val expand = onToggleMini ?: {}
            val actions = remember(model.viewModel, expand) {
                val vm = model.viewModel
                CallScreenActions(
                    acceptAudio = {}, acceptVideo = {}, decline = vm::decline, hangup = vm::hangup,
                    toggleMute = vm::toggleMute, toggleVideo = vm::toggleVideo, switchCamera = vm::switchCamera,
                    setRoute = vm::setRoute, onVerificationClick = vm::onVerificationClick, retry = {}, close = vm::close,
                    minimize = onMinimize, endFromKeyboard = vm::endFromKeyboard, selectDevice = vm::selectDevice, toggleMini = expand,
                )
            }
            LaunchedEffect(events.deviceMenu.value) {
                if (events.deviceMenu.value != null) {
                    events.deviceMenu.value = null
                    expand()
                }
            }
            Box(modifier.fillMaxSize()) {
                CallMiniContent(ui, name, avatar, actions, Modifier, onMiniDragStart, onMiniDrag)
                CallDeviceSnackbarHost(events.snackbar, Modifier.align(Alignment.BottomCenter))
            }
            CallEventDialogs(events, model.viewModel)
            return@CompositionLocalProvider
        }
        // The stage runs under the transparent title bar; only the chrome steps down by topInset.
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            CallOverlay(
                viewModel = model.viewModel,
                ui = ui,
                peerName = name,
                avatar = avatar,
                onMinimize = onMinimize,
                fillsWindow = true,
                topInset = topInset,
                onToggleMini = onToggleMini,
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
    // Mission Control, the taskbar and screen readers announce the window by this.
    val base = if (name.isEmpty()) name else stringResource(MR.string.call_window_title, name, stringResource(if (ui.isVideoCall) MR.string.call_button_video else MR.string.call_button_voice))
    val elapsed = callWindowElapsed(ui, now) ?: return base
    return stringResource(MR.string.call_pill_text, base, elapsed)
}

@Composable
fun OngoingCallBarHost(
    model: CallWindowModel,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
) {
    val ui by model.uiState.collectAsStateWithLifecycle()
    val miniOpen by model.miniWindowOpen.collectAsStateWithLifecycle()
    // Ringing out counts: the main window should already offer the way back to a call it just placed.
    if (!callIsLive(ui) || miniOpen) return
    val name = model.peerName(ui.peer)
    Box(modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(top = topInset)) {
        OngoingCallBar(
            ui = ui,
            peerName = name,
            onClick = model::focusCallWindow,
            onToggleMute = model.viewModel::toggleMute,
            onHangup = model.viewModel::hangup,
        )
    }
}

/** Lays the app content out under the desktop ongoing-call bar; with no [callWindow] the platform hosts the call overlay instead. */
@Composable
fun CallChrome(
    callWindow: CallWindowModel?,
    topInset: Dp,
    callHost: @Composable () -> Unit,
    content: @Composable (topInset: Dp) -> Unit,
) {
    val barShown = callWindow != null &&
        callWindow.live.collectAsStateWithLifecycle().value &&
        !callWindow.miniWindowOpen.collectAsStateWithLifecycle().value
    Column(Modifier.fillMaxSize()) {
        if (callWindow != null) OngoingCallBarHost(callWindow, topInset = topInset)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content(if (barShown) 0.dp else topInset)
            if (callWindow == null) callHost()
        }
    }
}
