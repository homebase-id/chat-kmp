package id.homebase.chat.calling

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallQuality
import id.homebase.chat.services.calling.CallState
import id.homebase.chat.services.calling.CallVideoView
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.FailReason
import id.homebase.chat.services.calling.FakeVideo
import id.homebase.chat.services.calling.VideoHandle
import id.homebase.resources.MR
import id.homebase.resources.call_accept
import id.homebase.resources.call_camera_off
import id.homebase.resources.call_camera_on
import id.homebase.resources.call_decline
import id.homebase.resources.call_ended_busy
import id.homebase.resources.call_ended_cancelled
import id.homebase.resources.call_ended_declined
import id.homebase.resources.call_ended_failed
import id.homebase.resources.call_ended_hangup
import id.homebase.resources.call_ended_missed
import id.homebase.resources.call_ended_no_route
import id.homebase.resources.call_ended_permission
import id.homebase.resources.call_hang_up
import id.homebase.resources.call_incoming_video
import id.homebase.resources.call_incoming_voice
import id.homebase.resources.call_label_camera
import id.homebase.resources.call_label_flip
import id.homebase.resources.call_label_mute
import id.homebase.resources.call_label_speaker
import id.homebase.resources.call_mute
import id.homebase.resources.call_poor_connection
import id.homebase.resources.call_route_earpiece
import id.homebase.resources.call_route_speaker
import id.homebase.resources.call_status_calling
import id.homebase.resources.call_status_connecting
import id.homebase.resources.call_status_reconnecting
import id.homebase.resources.call_status_ringing
import id.homebase.resources.call_switch_camera
import id.homebase.resources.call_unmute
import id.homebase.resources.call_verification_chip
import kotlin.time.Clock
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

class CallScreenActions(
    val accept: () -> Unit,
    val decline: () -> Unit,
    val hangup: () -> Unit,
    val setMuted: (Boolean) -> Unit,
    val setVideo: (Boolean) -> Unit,
    val switchCamera: () -> Unit,
    val setRoute: (AudioRoute) -> Unit,
    val onVerificationClick: () -> Unit,
)

private val ContentMaxWidth = 480.dp

@Composable
fun CallScreen(
    state: CallState,
    videos: CallVideos,
    peerName: String,
    avatar: @Composable (size: Dp) -> Unit,
    actions: CallScreenActions,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    val remoteHandle = videos.remote
    val showRemoteVideo = remoteHandle != null && state.media.remoteVideo &&
        (phase == CallPhase.Connected || phase == CallPhase.Reconnecting)

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        CallBackdrop()
        if (showRemoteVideo) {
            CallVideoSurface(remoteHandle, Modifier.fillMaxSize())
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
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize()) {
                when (phase) {
                    CallPhase.Incoming -> IncomingLayout(state, peerName, avatar, actions)
                    CallPhase.Outgoing -> OutgoingLayout(state, peerName, avatar, actions)
                    is CallPhase.Ended -> EndedLayout(phase.reason, peerName, avatar)
                    CallPhase.Idle -> Unit
                    else -> InCallLayout(state, videos, showRemoteVideo, peerName, avatar, actions)
                }
            }
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
    state: CallState,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    val video = state.media.remoteVideo || state.media.localVideo
    PeerHeader(
        name = peerName,
        status = stringResource(if (video) MR.string.call_incoming_video else MR.string.call_incoming_voice),
        avatar = avatar,
        pulsing = true,
        modifier = Modifier.align(Alignment.Center).padding(bottom = 96.dp),
    )
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 40.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
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
            icon = if (video) Icons.Default.Videocam else Icons.Default.Call,
            label = stringResource(MR.string.call_accept),
            description = stringResource(MR.string.call_accept),
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            onClick = actions.accept,
        )
    }
}

@Composable
private fun BoxScope.OutgoingLayout(
    state: CallState,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    PeerHeader(
        name = peerName,
        status = stringResource(if (state.peerRinging) MR.string.call_status_ringing else MR.string.call_status_calling),
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
    state: CallState,
    videos: CallVideos,
    showRemoteVideo: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    val reconnecting = state.phase == CallPhase.Reconnecting
    val elapsed = rememberElapsedText(state.connectedAtMs)
    val statusText = if (state.phase == CallPhase.Connecting) stringResource(MR.string.call_status_connecting) else elapsed

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
            pulsing = state.phase == CallPhase.Connecting,
            modifier = Modifier.align(Alignment.Center).padding(bottom = 140.dp),
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
        AnimatedVisibility(visible = !reconnecting && state.quality == CallQuality.Poor, enter = fadeIn(), exit = fadeOut()) {
            StatusPill(
                text = stringResource(MR.string.call_poor_connection),
                leading = { Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) },
            )
        }
        state.verificationCode?.let { code ->
            StatusPill(
                text = stringResource(MR.string.call_verification_chip, code),
                leading = { Icon(Icons.Default.VerifiedUser, null, Modifier.size(16.dp)) },
                onClick = actions.onVerificationClick,
            )
        }
    }

    val localHandle = videos.local
    if (state.media.localVideo && localHandle != null) {
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp).size(width = 104.dp, height = 148.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 6.dp,
        ) {
            CallVideoSurface(localHandle, Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
        }
    }

    ControlBar(state, videoCapable = videos.local != null, actions, Modifier.align(Alignment.BottomCenter))
}

@Composable
private fun ControlBar(state: CallState, videoCapable: Boolean, actions: CallScreenActions, modifier: Modifier) {
    val media = state.media
    val speaker = media.route == AudioRoute.Speaker
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
            ) {
                ToggleCallButton(
                    icon = if (media.muted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = stringResource(MR.string.call_label_mute),
                    description = stringResource(if (media.muted) MR.string.call_unmute else MR.string.call_mute),
                    active = media.muted,
                    onClick = { actions.setMuted(!media.muted) },
                )
                ToggleCallButton(
                    icon = if (speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.Hearing,
                    label = stringResource(MR.string.call_label_speaker),
                    description = stringResource(if (speaker) MR.string.call_route_earpiece else MR.string.call_route_speaker),
                    active = speaker,
                    onClick = { actions.setRoute(if (speaker) AudioRoute.Earpiece else AudioRoute.Speaker) },
                )
                if (videoCapable) {
                    ToggleCallButton(
                        icon = if (media.localVideo) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        label = stringResource(MR.string.call_label_camera),
                        description = stringResource(if (media.localVideo) MR.string.call_camera_off else MR.string.call_camera_on),
                        active = media.localVideo,
                        onClick = { actions.setVideo(!media.localVideo) },
                    )
                }
                if (media.localVideo) {
                    ToggleCallButton(
                        icon = Icons.Default.Cameraswitch,
                        label = stringResource(MR.string.call_label_flip),
                        description = stringResource(MR.string.call_switch_camera),
                        active = false,
                        onClick = actions.switchCamera,
                    )
                }
            }
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
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier.size(72.dp).semantics { contentDescription = description; role = Role.Button },
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
