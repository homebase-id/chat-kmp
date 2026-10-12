package id.homebase.chat.calling

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
private val StageMaxWidth = 960.dp
private val StageMaxHeight = 680.dp

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
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = StageMaxWidth).fillMaxWidth().heightIn(max = StageMaxHeight).fillMaxHeight(),
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                    shadowElevation = 12.dp,
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
                    0f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                    0.3f to Color.Transparent,
                    0.65f to Color.Transparent,
                    1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
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
            CallPhase.Incoming -> IncomingLayout(ui, peerName, avatar, actions)
            CallPhase.Outgoing -> OutgoingLayout(ui, peerName, avatar, actions)
            is CallPhase.Ended ->
                if (phase.reason is CallEndReason.Failed) FailedLayout(phase.reason.reason, peerName, avatar, actions)
                else EndedLayout(phase.reason, peerName, avatar)
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
                0f to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                0.55f to MaterialTheme.colorScheme.surfaceContainerHigh,
                1f to MaterialTheme.colorScheme.surface,
            ),
        ),
    )
}

@Composable
private fun BoxScope.IncomingLayout(
    ui: CallUiState,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    val video = ui.isVideoCall
    PeerHeader(
        name = peerName,
        status = stringResource(if (video) MR.string.call_incoming_video else MR.string.call_incoming_voice),
        avatar = avatar,
        pulsing = true,
        modifier = Modifier.align(Alignment.Center).padding(bottom = 96.dp),
    )
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 40.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
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
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
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
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    PeerHeader(
        name = peerName,
        status = stringResource(if (ui.peerRinging) MR.string.call_status_ringing else MR.string.call_status_calling),
        avatar = avatar,
        pulsing = true,
        modifier = Modifier.align(Alignment.Center).padding(bottom = 96.dp),
    )
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) {
        RoundCallButton(
            icon = Icons.Default.CallEnd,
            label = stringResource(MR.string.call_hang_up),
            description = stringResource(MR.string.call_hang_up),
            container = MaterialTheme.colorScheme.error,
            content = MaterialTheme.colorScheme.onError,
            onClick = actions.hangup,
        )
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
    val elapsed = rememberElapsedText(ui.connectedAtMs)
    val statusText = if (ui.phase == CallPhase.Connecting) stringResource(MR.string.call_status_connecting) else elapsed

    if (showRemoteVideo) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                peerName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    } else {
        PeerHeader(
            name = peerName,
            status = statusText,
            avatar = avatar,
            pulsing = ui.phase == CallPhase.Connecting,
            modifier = Modifier.align(Alignment.Center).padding(bottom = if (large) 96.dp else 140.dp),
        )
    }

    Column(
        Modifier.align(Alignment.TopCenter).padding(top = if (showRemoteVideo) 72.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(visible = reconnecting, enter = fadeIn(), exit = fadeOut()) {
            StatusPill(
                text = stringResource(MR.string.call_status_reconnecting),
                leading = { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) },
            )
        }
        AnimatedVisibility(visible = !reconnecting && ui.quality == CallQuality.Poor, enter = fadeIn(), exit = fadeOut()) {
            StatusPill(
                text = stringResource(MR.string.call_poor_connection),
                leading = { Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) },
            )
        }
        ui.verificationCode?.let { code ->
            StatusPill(
                text = stringResource(MR.string.call_verification_chip, code),
                leading = { Icon(Icons.Default.VerifiedUser, null, Modifier.size(16.dp)) },
                onClick = actions.onVerificationClick,
            )
        }
    }

    IconButton(onClick = actions.minimize, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(48.dp)) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(MR.string.call_minimize),
            tint = if (showRemoteVideo) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
        )
    }

    val localHandle = ui.videos.local
    if (ui.localVideo && localHandle != null) {
        LocalPreview(localHandle, large, bottomReserve = if (large) 150.dp else 310.dp)
    }

    ControlBar(ui, large, actions, Modifier.align(Alignment.BottomCenter))
}

@Composable
private fun BoxScope.LocalPreview(handle: VideoHandle, large: Boolean, bottomReserve: Dp) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.matchParentSize()) {
        val pipWidth = if (large) 180.dp else 104.dp
        val pipHeight = if (large) 252.dp else 148.dp
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val pipW = with(density) { pipWidth.toPx() }
        val pipH = with(density) { pipHeight.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        val top = with(density) { 72.dp.toPx() }
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
        Surface(
            modifier = Modifier
                .offset { (anchor.value + drag).round() }
                .size(pipWidth, pipHeight)
                .semantics { contentDescription = previewDescription }
                .pointerInput(width, height) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            drag += amount
                        },
                        onDragEnd = {
                            val landed = anchor.value + drag
                            val center = landed + Offset(pipW / 2, pipH / 2)
                            val target = corners.minBy { (it + Offset(pipW / 2, pipH / 2) - center).getDistance() }
                            drag = Offset.Zero
                            scope.launch {
                                anchor.snapTo(landed)
                                anchor.animateTo(target, spring())
                            }
                        },
                    )
                },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 6.dp,
        ) {
            CallVideoSurface(handle, Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
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
            active = ui.muted,
            onClick = actions.toggleMute,
        )
        if (ui.availableRoutes.size > 1) RoutePicker(ui, actions)
        if (ui.videos.local != null) {
            ToggleCallButton(
                icon = if (ui.localVideo) Icons.Default.Videocam else Icons.Default.VideocamOff,
                label = stringResource(MR.string.call_label_camera),
                description = stringResource(if (ui.localVideo) MR.string.call_camera_off else MR.string.call_camera_on),
                active = ui.localVideo,
                onClick = actions.toggleVideo,
            )
        }
        if (ui.localVideo) {
            ToggleCallButton(
                icon = Icons.Default.Cameraswitch,
                label = stringResource(MR.string.call_label_flip),
                description = stringResource(MR.string.call_switch_camera),
                active = false,
                onClick = actions.switchCamera,
            )
        }
    }
    if (large) {
        Surface(
            modifier = modifier.padding(bottom = 28.dp),
            shape = RoundedCornerShape(40.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                toggles()
                RoundCallButton(
                    icon = Icons.Default.CallEnd,
                    label = stringResource(MR.string.call_hang_up),
                    description = stringResource(MR.string.call_hang_up),
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError,
                    onClick = actions.hangup,
                    size = 60.dp,
                )
            }
        }
        return
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) { toggles() }
        }
        RoundCallButton(
            icon = Icons.Default.CallEnd,
            label = stringResource(MR.string.call_hang_up),
            description = stringResource(MR.string.call_hang_up),
            container = MaterialTheme.colorScheme.error,
            content = MaterialTheme.colorScheme.onError,
            onClick = actions.hangup,
        )
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
            active = ui.route != AudioRoute.Earpiece,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ui.availableRoutes.forEach { route ->
                DropdownMenuItem(
                    text = { Text(stringResource(routeLabel(route))) },
                    leadingIcon = { Icon(routeIcon(route), null) },
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
private fun BoxScope.EndedLayout(reason: CallEndReason, peerName: String, avatar: @Composable (Dp) -> Unit) {
    PeerHeader(
        name = peerName,
        status = endedText(reason),
        avatar = avatar,
        pulsing = false,
        modifier = Modifier.align(Alignment.Center),
    )
}

@Composable
private fun BoxScope.FailedLayout(
    reason: FailReason,
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
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 32.dp).padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        avatar(112.dp)
        Spacer(Modifier.height(16.dp))
        Text(peerName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    Row(
        Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = actions.close, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(MR.string.call_close))
        }
        Button(onClick = actions.retry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(MR.string.call_retry))
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
                style = MaterialTheme.typography.labelLarge,
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

@Composable
private fun PeerHeader(
    name: String,
    status: String,
    avatar: @Composable (Dp) -> Unit,
    pulsing: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
            if (pulsing) PulseRings()
            avatar(144.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            status,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PulseRings() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseProgress",
    )
    val color = MaterialTheme.colorScheme.primary
    repeat(3) { index ->
        val p = (progress + index / 3f) % 1f
        Box(
            Modifier.size(144.dp).scale(1f + p * 0.6f).alpha((1f - p) * 0.35f)
                .background(color, CircleShape),
        )
    }
}

@Composable
private fun StatusPill(text: String, leading: @Composable () -> Unit, onClick: (() -> Unit)? = null) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
        modifier = if (onClick != null) Modifier.clip(CircleShape).clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading()
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier.size(size).semantics { contentDescription = description; role = Role.Button },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(32.dp)) }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleCallButton(
    icon: ImageVector,
    label: String,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(60.dp).semantics { contentDescription = description; role = Role.Button },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(26.dp)) }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CallVideoSurface(handle: VideoHandle?, modifier: Modifier = Modifier) {
    when (handle) {
        null -> Unit
        is FakeVideo -> Box(modifier.background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
            Text(handle.initials, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
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
