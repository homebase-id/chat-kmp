package id.homebase.chat.calling

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.contacts.ContactInfoGateway
import id.homebase.api.common.OdinId
import id.homebase.chat.services.calling.CallPictureInPictureEffect
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.CallVideoRenderers
import id.homebase.chat.services.calling.LocalCallVideoRenderers
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CallOverlay(
    viewModel: CallViewModel,
    ui: CallUiState,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gate = rememberCallPermissionGate()
    val inPip = rememberInPictureInPicture()
    KeepScreenOn(ui.keepScreenAwake)
    CallPictureInPictureEffect(
        eligible = ui.pipEligible,
        remote = ui.videos.remote,
        local = ui.videos.local.takeIf { ui.localVideo },
        muted = ui.muted,
        onToggleMute = viewModel::toggleMute,
        onHangup = viewModel::hangup,
        onRestore = viewModel::restore,
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
    val actions = remember(viewModel, gate) {
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
            minimize = viewModel::minimize,
            endFromKeyboard = viewModel::endFromKeyboard,
        )
    }

    val renderers = remember { CallVideoRenderers() }
    DisposableEffect(renderers) { onDispose { renderers.keepOnly(emptyList()) } }
    LaunchedEffect(ui.videos) { renderers.keepOnly(listOfNotNull(ui.videos.remote, ui.videos.local)) }
    @Suppress("DEPRECATION")
    BackHandler(enabled = ui.visible && ui.inProgress && !ui.minimized && !inPip, onBack = viewModel::minimize)

    val surface = when {
        inPip -> CallSurface.PictureInPicture
        ui.tileVisible -> CallSurface.Tile
        else -> CallSurface.Full
    }
    CompositionLocalProvider(LocalCallVideoRenderers provides renderers) {
        Box(modifier) {
            CallLayer(visible = ui.visible && (!ui.minimized || surface != CallSurface.Full), surface = surface) { shown ->
                when (shown) {
                    CallSurface.Tile -> FloatingCallTile(
                        ui = ui,
                        peerName = peerName,
                        avatar = avatar,
                        onExpand = viewModel::restore,
                        onToggleMute = viewModel::toggleMute,
                        onHangup = viewModel::hangup,
                    )
                    CallSurface.PictureInPicture -> CallPipLayout(ui, avatar)
                    CallSurface.Full -> CallScreen(ui = ui, peerName = peerName, avatar = avatar, actions = actions, chromeHeld = showVerification || confirmEnd)
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

internal enum class CallSurface { Full, PictureInPicture, Tile }

/** A full-screen surface swallows every touch and stays until a finger on it lifts, so a press that outlives the ended screen's timer never lands on the app. */
@Composable
internal fun CallLayer(visible: Boolean, surface: CallSurface, content: @Composable (CallSurface) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val last = remember { arrayOf(surface) }
    if (visible) last[0] = surface
    val shown = last[0]
    AnimatedVisibility(
        visible = visible || pressed,
        enter = fadeIn() + slideInVertically { it / 8 },
        exit = fadeOut() + slideOutVertically { it / 8 },
    ) {
        if (shown == CallSurface.Tile) {
            content(shown)
        } else {
            Box(Modifier.fillMaxSize().holdWhilePressed { pressed = it }.swallowTaps()) { content(shown) }
        }
    }
}

private fun Modifier.holdWhilePressed(onPressed: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onPressed(true)
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        } finally {
            onPressed(false)
        }
    }
}

@Composable
internal fun rememberPeerName(odinId: String?): String {
    if (odinId == null) return ""
    val gateway = koinInject<ContactInfoGateway>()
    var name by remember(odinId) { mutableStateOf(odinId) }
    LaunchedEffect(odinId) {
        runCatching { gateway.displayName(OdinId(odinId)) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { name = it }
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

private fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }
