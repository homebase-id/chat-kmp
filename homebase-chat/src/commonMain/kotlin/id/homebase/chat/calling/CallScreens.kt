@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.chat.calling

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallQuality
import id.homebase.chat.services.calling.CallVideoView
import id.homebase.chat.services.calling.FailReason
import id.homebase.chat.services.calling.FakeVideo
import id.homebase.chat.services.calling.VideoHandle
import id.homebase.resources.MR
import id.homebase.resources.call_accept_audio
import id.homebase.resources.call_accept_audio_label
import id.homebase.resources.call_accept_video
import id.homebase.resources.call_accept_video_label
import id.homebase.resources.call_camera_off
import id.homebase.resources.call_camera_on
import id.homebase.resources.call_close
import id.homebase.resources.call_decline
import id.homebase.resources.call_ended_busy
import id.homebase.resources.call_ended_cancelled
import id.homebase.resources.call_ended_declined
import id.homebase.resources.call_ended_failed
import id.homebase.resources.call_ended_hangup
import id.homebase.resources.call_ended_missed
import id.homebase.resources.call_ended_no_route
import id.homebase.resources.call_ended_permission
import id.homebase.resources.call_failed_body
import id.homebase.resources.call_failed_other_body
import id.homebase.resources.call_failed_title
import id.homebase.resources.call_hang_up
import id.homebase.resources.call_incoming_video
import id.homebase.resources.call_incoming_voice
import id.homebase.resources.call_label_audio_output
import id.homebase.resources.call_label_camera
import id.homebase.resources.call_label_flip
import id.homebase.resources.call_label_mute
import id.homebase.resources.call_local_preview
import id.homebase.resources.call_minimize
import id.homebase.resources.call_mute
import id.homebase.resources.call_no_video
import id.homebase.resources.call_permission_body
import id.homebase.resources.call_permission_title
import id.homebase.resources.call_pill_description
import id.homebase.resources.call_pill_text
import id.homebase.resources.call_poor_connection
import id.homebase.resources.call_retry
import id.homebase.resources.call_route_bluetooth
import id.homebase.resources.call_route_earpiece
import id.homebase.resources.call_route_picker
import id.homebase.resources.call_route_speaker
import id.homebase.resources.call_route_wired
import id.homebase.resources.call_status_calling
import id.homebase.resources.call_status_connecting
import id.homebase.resources.call_status_reconnecting
import id.homebase.resources.call_status_ringing
import id.homebase.resources.call_switch_camera
import id.homebase.resources.call_unmute
import id.homebase.resources.call_verification_chip
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

class CallScreenActions(
    val acceptAudio: () -> Unit,
    val acceptVideo: () -> Unit,
    val decline: () -> Unit,
    val hangup: () -> Unit,
    val toggleMute: () -> Unit,
    val toggleVideo: () -> Unit,
    val switchCamera: () -> Unit,
    val setRoute: (AudioRoute) -> Unit,
    val onVerificationClick: () -> Unit,
    val retry: () -> Unit,
    val close: () -> Unit,
    val minimize: () -> Unit,
    val endFromKeyboard: () -> Unit,
)

private val ContentMaxWidth = 480.dp
private val LargeWidth = 840.dp
private val StageMaxWidth = 1040.dp
private val StageMaxHeight = 720.dp
private val PipTop = 92.dp

private enum class HaloMode { None, Ringing, Waiting }

@Composable
fun CallScreen(
    ui: CallUiState,
    peerName: String,
    avatar: @Composable (size: Dp) -> Unit,
    actions: CallScreenActions,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val showRemoteVideo = ui.videos.remote != null && ui.remoteVideo &&
        (ui.phase == CallPhase.Connected || ui.phase == CallPhase.Reconnecting)

    BoxWithConstraints(
        modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { handleCallKey(it, ui, actions) },
    ) {
        CallBackdrop()
        if (maxWidth >= LargeWidth) {
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = StageMaxWidth).fillMaxWidth().heightIn(max = StageMaxHeight).fillMaxHeight(),
                    shape = RoundedCornerShape(36.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 16.dp,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        RemoteVideoLayer(ui, showRemoteVideo)
                        CallLayouts(ui, showRemoteVideo, large = true, peerName, avatar, actions)
                    }
                }
            }
        } else {
            RemoteVideoLayer(ui, showRemoteVideo)
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize()) {
                    CallLayouts(ui, showRemoteVideo, large = false, peerName, avatar, actions)
                }
            }
        }
    }
}

private fun handleCallKey(event: KeyEvent, ui: CallUiState, actions: CallScreenActions): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val modifier = event.isMetaPressed || event.isCtrlPressed
    return when {
        event.key == Key.Escape || (modifier && event.key == Key.W) -> {
            actions.endFromKeyboard()
            true
        }
        !modifier && event.key == Key.M && ui.inProgress -> {
            actions.toggleMute()
            true
        }
        !modifier && event.key == Key.V && ui.inProgress && ui.videos.local != null -> {
            actions.toggleVideo()
            true
        }
        else -> false
    }
}

@Composable
private fun RemoteVideoLayer(ui: CallUiState, show: Boolean) {
    if (!show) return
    Box(Modifier.fillMaxSize()) {
        CallVideoSurface(ui.videos.remote, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
                    0.22f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),
                ),
            ),
        )
    }
}

@Composable
private fun CallLayouts(
    ui: CallUiState,
    showRemoteVideo: Boolean,
    large: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    Box(Modifier.fillMaxSize()) {
        when (val phase = ui.phase) {
            CallPhase.Incoming -> IncomingLayout(ui, large, peerName, avatar, actions)
            CallPhase.Outgoing -> OutgoingLayout(ui, large, peerName, avatar, actions)
            is CallPhase.Ended ->
                if (phase.reason is CallEndReason.Failed) FailedLayout(phase.reason.reason, large, peerName, avatar, actions)
                else EndedLayout(phase.reason, large, peerName, avatar)
            CallPhase.Idle -> Unit
            else -> InCallLayout(ui, showRemoteVideo, large, peerName, avatar, actions)
        }
    }
}

@Composable
private fun CallBackdrop() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                0.5f to MaterialTheme.colorScheme.surfaceContainer,
                1f to MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
        ),
    )
}

@Composable
private fun BoxScope.IncomingLayout(
    ui: CallUiState,
    large: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    val video = ui.isVideoCall
    PeerHeader(
        name = peerName,
        status = stringResource(if (video) MR.string.call_incoming_video else MR.string.call_incoming_voice),
        avatar = avatar,
        halo = HaloMode.Ringing,
        large = large,
        modifier = Modifier.align(Alignment.Center).padding(bottom = if (large) 120.dp else 150.dp),
    )
    Row(
        Modifier.align(Alignment.BottomCenter)
            .then(if (large) Modifier.padding(bottom = 56.dp) else Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp)),
        horizontalArrangement = if (large) Arrangement.spacedBy(64.dp) else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        RoundCallButton(
            icon = Icons.Default.CallEnd,
            label = stringResource(MR.string.call_decline),
            description = stringResource(MR.string.call_decline),
            container = MaterialTheme.colorScheme.error,
            content = MaterialTheme.colorScheme.onError,
            onClick = actions.decline,
        )
        RoundCallButton(
            icon = Icons.Default.Call,
            label = stringResource(MR.string.call_accept_audio_label),
            description = stringResource(MR.string.call_accept_audio),
            container = if (video) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
            content = if (video) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary,
            onClick = actions.acceptAudio,
        )
        if (video) {
            RoundCallButton(
                icon = Icons.Default.Videocam,
                label = stringResource(MR.string.call_accept_video_label),
                description = stringResource(MR.string.call_accept_video),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = actions.acceptVideo,
            )
        }
    }
}

@Composable
private fun BoxScope.OutgoingLayout(
    ui: CallUiState,
    large: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    PeerHeader(
        name = peerName,
        status = stringResource(if (ui.peerRinging) MR.string.call_status_ringing else MR.string.call_status_calling),
        avatar = avatar,
        halo = HaloMode.Ringing,
        large = large,
        modifier = Modifier.align(Alignment.Center).padding(bottom = if (large) 120.dp else 150.dp),
    )
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = if (large) 56.dp else 40.dp)) {
        EndCallButton(actions.hangup, large = false)
    }
}

@Composable
private fun BoxScope.InCallLayout(
    ui: CallUiState,
    showRemoteVideo: Boolean,
    large: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    val reconnecting = ui.phase == CallPhase.Reconnecting
    val connecting = ui.phase == CallPhase.Connecting
    val elapsed = rememberElapsedText(ui.connectedAtMs)

    if (showRemoteVideo) {
        VideoTopBar(peerName, elapsed, actions.minimize, Modifier.align(Alignment.TopCenter))
    } else {
        PeerHeader(
            name = peerName,
            status = if (connecting) stringResource(MR.string.call_status_connecting) else elapsed,
            statusIsTimer = !connecting,
            avatar = avatar,
            halo = if (connecting || reconnecting) HaloMode.Waiting else HaloMode.None,
            dimmed = reconnecting,
            large = large,
            modifier = Modifier.align(Alignment.Center).padding(bottom = if (large) 110.dp else 190.dp),
        )
        MinimizeButton(actions.minimize, overVideo = false, Modifier.align(Alignment.TopStart).padding(4.dp))
    }

    val localHandle = ui.videos.local
    val showPreview = ui.localVideo && localHandle != null
    val pillsAtStart = !large && (showRemoteVideo || showPreview)
    Column(
        Modifier.align(if (pillsAtStart) Alignment.TopStart else Alignment.TopCenter)
            .padding(top = if (showRemoteVideo) 76.dp else if (pillsAtStart) 60.dp else 16.dp)
            .padding(start = if (pillsAtStart) 16.dp else 64.dp, end = 64.dp),
        horizontalAlignment = if (pillsAtStart) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(visible = reconnecting, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            StatusPill(
                text = stringResource(MR.string.call_status_reconnecting),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                leading = { LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onTertiaryContainer) },
            )
        }
        AnimatedVisibility(
            visible = !reconnecting && ui.quality == CallQuality.Poor,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            StatusPill(
                text = stringResource(MR.string.call_poor_connection),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                leading = { Icon(Icons.Default.Warning, null, Modifier.size(18.dp)) },
            )
        }
        val peerCameraOff = !showRemoteVideo && ui.localVideo && !ui.remoteVideo && !connecting
        AnimatedVisibility(visible = peerCameraOff, enter = fadeIn(), exit = fadeOut()) {
            StatusPill(
                text = stringResource(MR.string.call_no_video),
                leading = { Icon(Icons.Default.VideocamOff, null, Modifier.size(18.dp)) },
            )
        }
        ui.verificationCode?.let { code ->
            StatusPill(
                text = stringResource(MR.string.call_verification_chip, code),
                leading = { Icon(Icons.Default.VerifiedUser, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                onClick = actions.onVerificationClick,
            )
        }
    }

    if (showPreview && localHandle != null) {
        LocalPreview(localHandle, large, bottomReserve = if (large) 150.dp else 268.dp)
    }

    ControlBar(ui, large, actions, Modifier.align(Alignment.BottomCenter))
}

@Composable
private fun VideoTopBar(peerName: String, elapsed: String, onMinimize: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        MinimizeButton(onMinimize, overVideo = true, Modifier.align(Alignment.CenterStart))
        Surface(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 60.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.86f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    peerName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    elapsed,
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MinimizeButton(onClick: () -> Unit, overVideo: Boolean, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = if (overVideo) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.86f) else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(MR.string.call_minimize))
        }
    }
}

@Composable
private fun BoxScope.LocalPreview(handle: VideoHandle, large: Boolean, bottomReserve: Dp) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val springSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Offset>()
    BoxWithConstraints(Modifier.matchParentSize()) {
        val pipWidth = if (large) 192.dp else 108.dp
        val pipHeight = if (large) 132.dp else 156.dp
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val pipW = with(density) { pipWidth.toPx() }
        val pipH = with(density) { pipHeight.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        val top = with(density) { PipTop.toPx() }
        val bottom = with(density) { bottomReserve.toPx() }
        val previewDescription = stringResource(MR.string.call_local_preview)
        val corners = listOf(
            Offset(margin, top),
            Offset(width - pipW - margin, top),
            Offset(margin, height - pipH - bottom),
            Offset(width - pipW - margin, height - pipH - bottom),
        )
        val anchor = remember(width, height) { Animatable(corners[1], Offset.VectorConverter) }
        var drag by remember(width, height) { mutableStateOf(Offset.Zero) }
        val shape = RoundedCornerShape(if (large) 20.dp else 22.dp)
        Surface(
            modifier = Modifier
                .offset { (anchor.value + drag).round() }
                .size(pipWidth, pipHeight)
                .semantics { contentDescription = previewDescription }
                .pointerInput(width, height, rtl) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            drag += if (rtl) Offset(-amount.x, amount.y) else amount
                        },
                        onDragEnd = {
                            val landed = anchor.value + drag
                            val center = landed + Offset(pipW / 2, pipH / 2)
                            val target = corners.minBy { (it + Offset(pipW / 2, pipH / 2) - center).getDistance() }
                            drag = Offset.Zero
                            scope.launch {
                                anchor.snapTo(landed)
                                anchor.animateTo(target, springSpec)
                            }
                        },
                    )
                },
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)),
            shadowElevation = 12.dp,
        ) {
            CallVideoSurface(handle, Modifier.fillMaxSize().clip(shape), local = true)
        }
    }
}

@Composable
private fun ControlBar(ui: CallUiState, large: Boolean, actions: CallScreenActions, modifier: Modifier) {
    val toggles: @Composable () -> Unit = {
        ToggleCallButton(
            icon = if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic,
            label = stringResource(MR.string.call_label_mute),
            description = stringResource(if (ui.muted) MR.string.call_unmute else MR.string.call_mute),
            checked = ui.muted,
            onClick = actions.toggleMute,
        )
        if (ui.availableRoutes.size > 1) RoutePicker(ui, actions)
        if (ui.videos.local != null) {
            ToggleCallButton(
                icon = if (ui.localVideo) Icons.Default.Videocam else Icons.Default.VideocamOff,
                label = stringResource(MR.string.call_label_camera),
                description = stringResource(if (ui.localVideo) MR.string.call_camera_off else MR.string.call_camera_on),
                checked = ui.localVideo,
                onClick = actions.toggleVideo,
            )
        }
        if (ui.localVideo) {
            ToggleCallButton(
                icon = Icons.Default.Cameraswitch,
                label = stringResource(MR.string.call_label_flip),
                description = stringResource(MR.string.call_switch_camera),
                checked = null,
                onClick = actions.switchCamera,
            )
        }
    }
    if (large) {
        Surface(
            modifier = modifier.padding(bottom = 28.dp),
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.94f),
            shadowElevation = 10.dp,
        ) {
            Row(
                Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                toggles()
                VerticalDivider(Modifier.height(48.dp).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                EndCallButton(actions.hangup, large = true)
            }
        }
        return
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.9f),
            shadowElevation = 2.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) { toggles() }
        }
        EndCallButton(actions.hangup, large = false)
    }
}

@Composable
private fun RoutePicker(ui: CallUiState, actions: CallScreenActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToggleCallButton(
            icon = routeIcon(ui.route),
            label = stringResource(MR.string.call_label_audio_output),
            description = stringResource(MR.string.call_route_picker),
            checked = ui.route != AudioRoute.Earpiece,
            onClick = { expanded = true },
            menu = true,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ui.availableRoutes.forEach { route ->
                DropdownMenuItem(
                    text = { Text(stringResource(routeLabel(route))) },
                    leadingIcon = { Icon(routeIcon(route), null) },
                    trailingIcon = if (route == ui.route) {
                        { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        expanded = false
                        actions.setRoute(route)
                    },
                )
            }
        }
    }
}

private fun routeIcon(route: AudioRoute): ImageVector = when (route) {
    AudioRoute.Earpiece -> Icons.Default.Hearing
    AudioRoute.Speaker -> Icons.AutoMirrored.Filled.VolumeUp
    AudioRoute.Bluetooth -> Icons.Default.Bluetooth
    AudioRoute.Wired -> Icons.Default.Headset
}

private fun routeLabel(route: AudioRoute) = when (route) {
    AudioRoute.Earpiece -> MR.string.call_route_earpiece
    AudioRoute.Speaker -> MR.string.call_route_speaker
    AudioRoute.Bluetooth -> MR.string.call_route_bluetooth
    AudioRoute.Wired -> MR.string.call_route_wired
}

@Composable
private fun BoxScope.EndedLayout(reason: CallEndReason, large: Boolean, peerName: String, avatar: @Composable (Dp) -> Unit) {
    Column(
        Modifier.align(Alignment.Center).padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PeerHeader(
            name = peerName,
            status = null,
            avatar = avatar,
            halo = HaloMode.None,
            dimmed = true,
            large = large,
        )
        Spacer(Modifier.height(20.dp))
        StatusPill(
            text = endedText(reason),
            leading = { Icon(Icons.Default.CallEnd, null, Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun BoxScope.FailedLayout(
    reason: FailReason,
    large: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    val title = stringResource(
        when (reason) {
            FailReason.NoRoute -> MR.string.call_failed_title
            FailReason.Permission -> MR.string.call_permission_title
            FailReason.Other -> MR.string.call_ended_failed
        },
    )
    val body = stringResource(
        when (reason) {
            FailReason.NoRoute -> MR.string.call_failed_body
            FailReason.Permission -> MR.string.call_permission_body
            FailReason.Other -> MR.string.call_failed_other_body
        },
    )
    val badge = when (reason) {
        FailReason.NoRoute -> Icons.Default.WifiOff
        FailReason.Permission -> Icons.Default.MicOff
        FailReason.Other -> Icons.Default.ErrorOutline
    }
    Column(
        Modifier.align(Alignment.Center).widthIn(max = 420.dp).padding(horizontal = 32.dp).padding(bottom = if (large) 72.dp else 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(132.dp)) {
            Box(Modifier.align(Alignment.Center).alpha(0.6f)) { avatar(112.dp) }
            val badgeShape = MaterialShapes.Cookie9Sided.toShape()
            Box(
                Modifier.align(Alignment.BottomEnd).size(52.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, badgeShape)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, badgeShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(badge, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            peerName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    Column(
        Modifier.align(Alignment.BottomCenter).widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = if (large) 48.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = actions.retry,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
        ) {
            Icon(Icons.Default.Refresh, null, Modifier.size(ButtonDefaults.iconSizeFor(56.dp)))
            Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(56.dp)))
            Text(stringResource(MR.string.call_retry), style = ButtonDefaults.textStyleFor(56.dp))
        }
        TextButton(onClick = actions.close, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(MR.string.call_close))
        }
    }
}

@Composable
fun OngoingCallPill(ui: CallUiState, peerName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val elapsed = rememberElapsedText(ui.connectedAtMs)
    val description = stringResource(MR.string.call_pill_description, peerName)
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description; role = Role.Button },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Call, null, Modifier.size(18.dp))
            Text(
                stringResource(MR.string.call_pill_text, peerName, elapsed),
                style = MaterialTheme.typography.labelLarge.tabular(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun endedText(reason: CallEndReason): String = stringResource(
    when (reason) {
        is CallEndReason.Hangup -> MR.string.call_ended_hangup
        CallEndReason.Declined -> MR.string.call_ended_declined
        CallEndReason.Missed -> MR.string.call_ended_missed
        CallEndReason.Busy -> MR.string.call_ended_busy
        CallEndReason.Cancelled -> MR.string.call_ended_cancelled
        is CallEndReason.Failed -> when (reason.reason) {
            FailReason.NoRoute -> MR.string.call_ended_no_route
            FailReason.Permission -> MR.string.call_ended_permission
            FailReason.Other -> MR.string.call_ended_failed
        }
    },
)

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

@Composable
private fun PeerHeader(
    name: String,
    status: String?,
    avatar: @Composable (Dp) -> Unit,
    halo: HaloMode,
    large: Boolean,
    modifier: Modifier = Modifier,
    statusIsTimer: Boolean = false,
    dimmed: Boolean = false,
) {
    val avatarSize = if (large) 152.dp else 136.dp
    val avatarAlpha by animateFloatAsState(if (dimmed) 0.55f else 1f, MaterialTheme.motionScheme.defaultEffectsSpec())
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(avatarSize * 1.5f), contentAlignment = Alignment.Center) {
            if (halo != HaloMode.None) AvatarHalo(avatarSize, halo)
            Box(Modifier.alpha(avatarAlpha)) { avatar(avatarSize) }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            name,
            style = if (large) MaterialTheme.typography.displaySmallEmphasized else MaterialTheme.typography.headlineLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                status,
                style = if (statusIsTimer) MaterialTheme.typography.titleLargeEmphasized.tabular() else MaterialTheme.typography.titleMedium,
                color = if (statusIsTimer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AvatarHalo(avatarSize: Dp, mode: HaloMode) {
    val transition = rememberInfiniteTransition(label = "halo")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (mode == HaloMode.Ringing) 14_000 else 6_000, easing = LinearEasing)),
        label = "haloRotation",
    )
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Reverse),
        label = "haloBreath",
    )
    val cookie = MaterialShapes.Cookie12Sided.toShape()
    val ringColor = MaterialTheme.colorScheme.primary
    if (mode == HaloMode.Ringing) {
        Box(
            Modifier.size(avatarSize * 1.5f).graphicsLayer {
                val s = 0.86f + breath * 0.14f
                scaleX = s
                scaleY = s
                alpha = (1f - breath) * 0.22f
            }.background(ringColor, CircleShape),
        )
    }
    Box(
        Modifier.size(avatarSize * 1.24f).graphicsLayer {
            rotationZ = rotation
            val s = 0.97f + breath * 0.05f
            scaleX = s
            scaleY = s
        }.background(
            if (mode == HaloMode.Ringing) ringColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            cookie,
        ),
    )
}

@Composable
private fun StatusPill(
    text: String,
    leading: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    val body: @Composable () -> Unit = {
        Row(
            Modifier.heightIn(min = if (onClick != null) 48.dp else 36.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading()
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = CircleShape, color = container, contentColor = content, content = body)
    } else {
        Surface(shape = CircleShape, color = container, contentColor = content, content = body)
    }
}

@Composable
private fun pressMorphCorner(size: Dp, pressed: Boolean, checked: Boolean = false): Dp {
    val target = when {
        pressed -> size * 0.22f
        checked -> size * 0.3f
        else -> size / 2
    }
    val corner by animateDpAsState(target, MaterialTheme.motionScheme.fastSpatialSpec())
    return corner
}

@Composable
private fun RoundCallButton(
    icon: ImageVector,
    label: String,
    description: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    size: Dp = 72.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner = pressMorphCorner(size, pressed)
    Column(
        Modifier.widthIn(min = 88.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(corner),
            color = container,
            contentColor = content,
            interactionSource = interaction,
            modifier = Modifier.size(size).semantics { contentDescription = description; role = Role.Button },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(32.dp)) }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun EndCallButton(onClick: () -> Unit, large: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val height = if (large) 56.dp else 64.dp
    val corner = pressMorphCorner(height, pressed)
    val description = stringResource(MR.string.call_hang_up)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        interactionSource = interaction,
        modifier = Modifier.size(width = if (large) 88.dp else 112.dp, height = height)
            .semantics { contentDescription = description; role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CallEnd, null, Modifier.size(if (large) 28.dp else 32.dp)) }
    }
}

/** [checked] null marks a momentary action (flip camera) that never shows an on state. */
@Composable
private fun ToggleCallButton(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean?,
    onClick: () -> Unit,
    menu: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val size = 60.dp
    val on = checked == true
    val corner = pressMorphCorner(size, pressed, on)
    val container by animateColorAsState(
        if (on) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
        MaterialTheme.motionScheme.fastEffectsSpec(),
    )
    val content by animateColorAsState(
        if (on) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
        MaterialTheme.motionScheme.fastEffectsSpec(),
    )
    Column(
        Modifier.widthIn(min = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val buttonModifier = Modifier.size(size).semantics { contentDescription = description }
        val inner: @Composable () -> Unit = {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(26.dp)) }
        }
        if (checked != null && !menu) {
            Surface(
                checked = checked,
                onCheckedChange = { onClick() },
                shape = RoundedCornerShape(corner),
                color = container,
                contentColor = content,
                interactionSource = interaction,
                modifier = buttonModifier,
                content = inner,
            )
        } else {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(corner),
                color = container,
                contentColor = content,
                interactionSource = interaction,
                modifier = buttonModifier.semantics { role = if (menu) Role.DropdownList else Role.Button },
                content = inner,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun CallVideoSurface(handle: VideoHandle?, modifier: Modifier = Modifier, local: Boolean = false) {
    when (handle) {
        null -> Unit
        is FakeVideo -> {
            val container = if (local) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer
            val content = if (local) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
            Box(modifier.background(container), contentAlignment = Alignment.Center) {
                Text(
                    handle.initials,
                    style = if (local) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayLarge,
                    color = content,
                )
            }
        }
        else -> CallVideoView(handle, modifier)
    }
}

@Composable
private fun rememberElapsedText(connectedAtMs: Long?): String {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(connectedAtMs) {
        while (true) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    return formatCallDuration(((now - (connectedAtMs ?: now)) / 1000).coerceAtLeast(0))
}

internal fun formatCallDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    val mm = m.toString().padStart(2, '0')
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
}
