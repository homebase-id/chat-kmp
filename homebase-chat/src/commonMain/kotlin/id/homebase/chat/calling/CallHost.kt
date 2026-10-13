package id.homebase.chat.calling

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallPictureInPictureEffect
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.RemoteVideoSize
import id.homebase.chat.services.calling.rememberInPictureInPicture
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.call_end_confirm_body
import id.homebase.resources.call_end_confirm_title
import id.homebase.resources.call_device_disconnected
import id.homebase.resources.call_device_change
import id.homebase.resources.call_device_returned
import id.homebase.resources.call_device_switch_failed
import id.homebase.resources.call_device_switch_failed_using
import id.homebase.resources.call_device_switch_back
import id.homebase.chat.services.calling.MediaDeviceKind
import id.homebase.chat.services.calling.MediaDeviceNotice
import id.homebase.resources.call_end_keep
import id.homebase.resources.call_hang_up
import id.homebase.resources.call_verification_body
import id.homebase.resources.call_verification_ok
import id.homebase.resources.call_verification_title
import id.homebase.core.util.KeepScreenOn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun CallHost(viewModel: CallViewModel, modifier: Modifier = Modifier) {
    if (!koinInject<CallService>().available) return
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val name = rememberPeerName(ui.peer)
    CallOverlay(
        viewModel = viewModel,
        ui = ui,
        peerName = name,
        avatar = { size -> ui.peer?.let { PeerAvatar(it, name, size) } },
        modifier = modifier,
    )
}

@Composable
fun CallOverlay(
    viewModel: CallViewModel,
    ui: CallUiState,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    modifier: Modifier = Modifier,
    onMinimize: () -> Unit = viewModel::minimize,
    fillsWindow: Boolean = false,
    topInset: Dp = 0.dp,
    onToggleMini: (() -> Unit)? = null,
) {
    val gate = rememberCallPermissionGate()
    val inPip = rememberInPictureInPicture()
    KeepScreenOn(ui.keepScreenAwake)
    CallPictureInPictureEffect(
        eligible = ui.pipEligible,
        remote = ui.videos.remote,
        muted = ui.muted,
        onToggleMute = viewModel::toggleMute,
        onHangup = viewModel::hangup,
    )
    LaunchedEffect(ui.inProgress) { if (!ui.inProgress) RemoteVideoSize.current.value = null }
    val events = rememberCallEvents(viewModel)
    val snackbar = events.snackbar
    val deviceMenu = events.deviceMenu
    val hold = remember { ChromeHold() }
    CompositionLocalProvider(LocalChromeHold provides hold) { HoldChromeWhile(events.dialogOpen) }
    val actions = remember(viewModel, gate, onMinimize, onToggleMini) {
        CallScreenActions(
            acceptAudio = { gate.run(video = false) { viewModel.accept(withVideo = false) } },
            acceptVideo = { gate.run(video = true) { video -> viewModel.accept(withVideo = video) } },
            decline = viewModel::decline,
            hangup = viewModel::hangup,
            toggleMute = viewModel::toggleMute,
            toggleVideo = viewModel::toggleVideo,
            switchCamera = viewModel::switchCamera,
            setRoute = viewModel::setRoute,
            onVerificationClick = viewModel::onVerificationClick,
            retry = { gate.run(video = viewModel.uiState.value.localVideo) { video -> viewModel.retry(video) } },
            close = viewModel::close,
            minimize = onMinimize,
            endFromKeyboard = viewModel::endFromKeyboard,
            selectDevice = viewModel::selectDevice,
            toggleMini = onToggleMini,
        )
    }

    Box(modifier) {
        AnimatedVisibility(
            visible = ui.visible && (!ui.minimized || inPip),
            enter = fadeIn() + slideInVertically { it / 8 },
            exit = fadeOut() + slideOutVertically { it / 8 },
        ) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
                if (inPip) CallPipLayout(ui, avatar) else CallScreen(ui = ui, peerName = peerName, avatar = avatar, actions = actions, fillsWindow = fillsWindow, topInset = topInset, deviceMenu = deviceMenu, hold = hold)
            }
        }
        if (ui.visible && !inPip) {
            CallDeviceSnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = topInset + SnackbarBelowNamePill))
        }
        if (ui.pillVisible && !inPip) {
            OngoingCallPill(
                ui = ui,
                peerName = peerName,
                onClick = viewModel::restore,
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp),
            )
        }
    }

    CallEventDialogs(events, viewModel)
}

internal class CallEventState {
    var verification by mutableStateOf(false)
    var confirmEnd by mutableStateOf(false)
    val snackbar = SnackbarHostState()
    val deviceMenu = mutableStateOf<MediaDeviceKind?>(null)
    val dialogOpen get() = verification || confirmEnd
}

@Composable
internal fun rememberCallEvents(viewModel: CallViewModel): CallEventState {
    val state = remember { CallEventState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CallUiEvent.ShowVerification -> state.verification = true
                CallUiEvent.ConfirmEnd -> { state.deviceMenu.value = null; state.confirmEnd = true }
                is CallUiEvent.Device -> scope.launch { showDeviceNotice(state, event.notice, viewModel) }
            }
        }
    }
    return state
}

private suspend fun showDeviceNotice(state: CallEventState, event: MediaDeviceNotice, viewModel: CallViewModel) {
    when (event) {
        is MediaDeviceNotice.Disconnected -> {
            val visuals = DeviceSnackbarVisuals(getString(MR.string.call_device_disconnected, event.name, event.fallbackName), getString(MR.string.call_device_change), event.kind)
            // The speaker list lives in the mic's audio menu, so both open at the mic chevron.
            if (state.snackbar.showSnackbar(visuals) == SnackbarResult.ActionPerformed) {
                state.deviceMenu.value = if (event.kind == MediaDeviceKind.Camera) MediaDeviceKind.Camera else MediaDeviceKind.Mic
            }
        }
        is MediaDeviceNotice.SwitchFailed -> {
            val message = event.stillUsingName?.let { getString(MR.string.call_device_switch_failed_using, event.name, it) }
                ?: getString(MR.string.call_device_switch_failed, event.name)
            state.snackbar.showSnackbar(DeviceSnackbarVisuals(message, null, event.kind))
        }
        is MediaDeviceNotice.Returned -> {
            val visuals = DeviceSnackbarVisuals(getString(MR.string.call_device_returned, event.name), getString(MR.string.call_device_switch_back), event.kind)
            if (state.snackbar.showSnackbar(visuals) == SnackbarResult.ActionPerformed) viewModel.selectDevice(event.kind, event.id)
        }
    }
}

@Composable
internal fun CallEventDialogs(events: CallEventState, viewModel: CallViewModel) {
    if (events.verification) {
        AlertDialog(
            onDismissRequest = { events.verification = false },
            title = { Text(stringResource(MR.string.call_verification_title)) },
            text = { Text(stringResource(MR.string.call_verification_body)) },
            confirmButton = {
                TextButton(onClick = { events.verification = false }) { Text(stringResource(MR.string.call_verification_ok)) }
            },
        )
    }
    if (events.confirmEnd) {
        AlertDialog(
            onDismissRequest = { events.confirmEnd = false },
            title = { Text(stringResource(MR.string.call_end_confirm_title)) },
            text = { Text(stringResource(MR.string.call_end_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = { events.confirmEnd = false; viewModel.hangup() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(MR.string.call_hang_up)) }
            },
            dismissButton = { TextButton(onClick = { events.confirmEnd = false }) { Text(stringResource(MR.string.call_end_keep)) } },
        )
    }
}

@Composable
internal fun rememberPeerName(odinId: String?): String {
    if (odinId == null) return ""
    val gateway = koinInject<ContactInfoGateway>()
    return rememberPeerName(odinId) { gateway.displayName(it) }
}

@Composable
internal fun rememberPeerName(odinId: String, displayName: suspend (OdinId) -> String?): String {
    var name by remember(odinId) { mutableStateOf(odinId) }
    LaunchedEffect(odinId) {
        runCatching { displayName(OdinId(odinId)) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { name = it }
    }
    return name
}

@Composable
internal fun PeerAvatar(odinId: String, name: String, size: Dp) {
    ContactAvatar(
        odinId = OdinId(odinId),
        profileImageData = null,
        initials = name.initials(),
        options = AvatarOptions(size = size, fontSize = (size.value / 3).sp),
    )
}

/** Under the name pill, clear of the dock: a notice there never sits on the controls it asks the user to use. */
private val SnackbarBelowNamePill = 80.dp
