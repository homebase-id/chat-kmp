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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import id.homebase.resources.call_end_keep
import id.homebase.resources.call_hang_up
import id.homebase.resources.call_verification_body
import id.homebase.resources.call_verification_ok
import id.homebase.resources.call_verification_title
import id.homebase.core.util.KeepScreenOn
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
    var showVerification by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CallUiEvent.ShowVerification -> showVerification = true
                CallUiEvent.ConfirmEnd -> confirmEnd = true
            }
        }
    }
    val actions = remember(viewModel, gate, onMinimize) {
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
        )
    }

    Box(modifier) {
        AnimatedVisibility(
            visible = ui.visible && (!ui.minimized || inPip),
            enter = fadeIn() + slideInVertically { it / 8 },
            exit = fadeOut() + slideOutVertically { it / 8 },
        ) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
                if (inPip) CallPipLayout(ui, avatar) else CallScreen(ui = ui, peerName = peerName, avatar = avatar, actions = actions, fillsWindow = fillsWindow, topInset = topInset)
            }
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

    if (showVerification) {
        AlertDialog(
            onDismissRequest = { showVerification = false },
            title = { Text(stringResource(MR.string.call_verification_title)) },
            text = { Text(stringResource(MR.string.call_verification_body)) },
            confirmButton = {
                TextButton(onClick = { showVerification = false }) { Text(stringResource(MR.string.call_verification_ok)) }
            },
        )
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(MR.string.call_end_confirm_title)) },
            text = { Text(stringResource(MR.string.call_end_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; viewModel.hangup() }) { Text(stringResource(MR.string.call_hang_up)) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(MR.string.call_end_keep)) } },
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
