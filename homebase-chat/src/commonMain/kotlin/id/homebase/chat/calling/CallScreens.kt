@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.chat.calling

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButtonShapes
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.call_accept_audio_from
import id.homebase.resources.call_accept_audio_label
import id.homebase.resources.call_accept_video_from
import id.homebase.resources.call_accept_video_label
import id.homebase.resources.call_call_back
import id.homebase.resources.call_camera_off
import id.homebase.resources.call_camera_on
import id.homebase.resources.call_close
import id.homebase.resources.call_decline
import id.homebase.resources.call_decline_from
import id.homebase.resources.call_ended_busy
import id.homebase.resources.call_ended_cancelled
import id.homebase.resources.call_ended_declined
import id.homebase.resources.call_ended_failed
import id.homebase.resources.call_ended_hangup
import id.homebase.resources.call_ended_missed
import id.homebase.resources.call_ended_no_route
import id.homebase.resources.call_ended_permission
import id.homebase.resources.call_event_duration
import id.homebase.resources.call_failed_body
import id.homebase.resources.call_failed_other_body
import id.homebase.resources.call_failed_title
import id.homebase.resources.call_hang_up
import id.homebase.resources.call_incoming_video
import id.homebase.resources.call_incoming_voice
import id.homebase.resources.call_label_camera
import id.homebase.resources.call_label_flip
import id.homebase.resources.call_label_mute
import id.homebase.resources.call_label_speaker
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
import id.homebase.resources.call_route_name_bluetooth
import id.homebase.resources.call_route_name_earpiece
import id.homebase.resources.call_route_name_wired
import id.homebase.resources.call_route_picker
import id.homebase.resources.call_route_speaker
import id.homebase.resources.call_route_wired
import id.homebase.resources.call_state_camera_on
import id.homebase.resources.call_state_mic_on
import id.homebase.resources.call_state_muted
import id.homebase.resources.call_status_calling
import id.homebase.resources.call_status_connecting
import id.homebase.resources.call_status_reconnecting
import id.homebase.resources.call_status_ringing
import id.homebase.resources.call_switch_camera
import id.homebase.resources.call_unmute
import id.homebase.resources.call_verification_chip
import id.homebase.resources.call_verification_show
import id.homebase.resources.call_you
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
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
private val PipTop = 84.dp
private val PhoneDockReserve = 236.dp
private val DesktopDockReserve = 128.dp

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
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    shadowElevation = 3.dp,
                ) {
                    CallStage(ui, large = true, peerName, avatar, actions, Modifier)
                }
            }
        } else {
            CallStage(ui, large = false, peerName, avatar, actions, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
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

/** [contentInsets] pads the controls only: video stays full-bleed under the system bars. */
@Composable
private fun CallStage(
    ui: CallUiState,
    large: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
    contentInsets: Modifier,
) {
    val videoMode = ui.isVideoCall && ui.inProgress
    Box(Modifier.fillMaxSize()) {
        if (videoMode) VideoStage(ui, large, avatar)
        Box(contentInsets.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.then(if (large) Modifier else Modifier.widthIn(max = ContentMaxWidth)).fillMaxSize()) {
                when (val phase = ui.phase) {
                    CallPhase.Incoming -> IncomingLayout(ui, large, peerName, avatar, actions)
                    CallPhase.Outgoing -> OutgoingLayout(ui, large, peerName, avatar, actions)
                    is CallPhase.Ended ->
                        if (phase.reason is CallEndReason.Failed) FailedLayout(phase.reason.reason, large, peerName, avatar, actions)
                        else EndedLayout(ui, phase.reason, large, peerName, avatar, actions)
                    CallPhase.Idle -> Unit
                    else -> InCallLayout(ui, videoMode, large, peerName, avatar, actions)
                }
            }
        }
        if (videoMode) ui.videos.local?.let { LocalPreview(it, ui.localVideo, large) }
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

private fun CallUiState.remoteShowing(): Boolean =
    videos.remote != null && remoteVideo && (phase == CallPhase.Connected || phase == CallPhase.Reconnecting)

@Composable
private fun VideoStage(ui: CallUiState, large: Boolean, avatar: @Composable (Dp) -> Unit) {
    val reconnecting = ui.phase == CallPhase.Reconnecting
    Box(Modifier.fillMaxSize()) {
        if (ui.remoteShowing()) {
            CallVideoSurface(ui.videos.remote, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.36f),
                        0.2f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.44f),
                    ),
                ),
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val size = if (large) 168.dp else 152.dp
                Box(
                    Modifier.size(size * 1.5f).padding(bottom = if (large) 24.dp else 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ui.phase == CallPhase.Connecting) AvatarHalo(size, HaloMode.Waiting)
                    avatar(size)
                }
            }
        }
        AnimatedVisibility(reconnecting, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LoadingIndicator(Modifier.size(48.dp), color = MaterialTheme.colorScheme.tertiary)
                        Text(stringResource(MR.string.call_status_reconnecting), style = MaterialTheme.typography.titleMediumEmphasized)
                    }
                }
            }
        }
    }
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
        modifier = Modifier.align(Alignment.Center).padding(bottom = if (large) 120.dp else 160.dp),
    )
    Row(
        Modifier.align(Alignment.BottomCenter)
            .then(if (large) Modifier.padding(bottom = 56.dp) else Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)),
        horizontalArrangement = if (large) Arrangement.spacedBy(64.dp) else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        RoundCallButton(
            icon = Icons.Default.CallEnd,
            label = stringResource(MR.string.call_decline),
            description = stringResource(MR.string.call_decline_from, peerName),
            container = HomebaseTheme.extendedColors.cameraRecord,
            content = HomebaseTheme.extendedColors.onCameraRecord,
            onClick = actions.decline,
        )
        RoundCallButton(
            icon = Icons.Default.Call,
            label = stringResource(MR.string.call_accept_audio_label),
            description = stringResource(MR.string.call_accept_audio_from, peerName),
            container = if (video) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
            content = if (video) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary,
            onClick = actions.acceptAudio,
        )
        if (video) {
            RoundCallButton(
                icon = Icons.Default.Videocam,
                label = stringResource(MR.string.call_accept_video_label),
                description = stringResource(MR.string.call_accept_video_from, peerName),
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
        modifier = Modifier.align(Alignment.Center).padding(bottom = if (large) 120.dp else 190.dp),
    )
    ControlDock(ui, large, actions, Modifier.align(Alignment.BottomCenter), placing = true)
}

@Composable
private fun BoxScope.InCallLayout(
    ui: CallUiState,
    videoMode: Boolean,
    large: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    val reconnecting = ui.phase == CallPhase.Reconnecting
    val connecting = ui.phase == CallPhase.Connecting
    val elapsed = rememberElapsedText(ui.connectedAtMs)

    if (!videoMode) {
        Column(
            Modifier.align(Alignment.Center).padding(bottom = if (large) 110.dp else 200.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PeerHeader(
                name = peerName,
                status = null,
                avatar = avatar,
                halo = if (connecting || reconnecting) HaloMode.Waiting else HaloMode.None,
                dimmed = reconnecting,
                large = large,
            )
            Spacer(Modifier.height(8.dp))
            AudioCallStatus(ui, elapsed)
        }
    }

    CallTopBar(
        ui = ui,
        center = if (videoMode) ({ NamePill(ui, peerName, elapsed) }) else null,
        actions = actions,
        modifier = Modifier.align(Alignment.TopCenter),
    )

    ControlDock(ui, large, actions, Modifier.align(Alignment.BottomCenter), placing = false)
}

@Composable
private fun AudioCallStatus(ui: CallUiState, elapsed: String) {
    when (ui.phase) {
        CallPhase.Connecting -> Text(
            stringResource(MR.string.call_status_connecting),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CallPhase.Reconnecting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoadingIndicator(Modifier.size(36.dp), color = MaterialTheme.colorScheme.tertiary)
            Text(
                stringResource(MR.string.call_status_reconnecting),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(elapsed, style = MaterialTheme.typography.titleMedium.tabular(), color = MaterialTheme.colorScheme.primary)
            AnimatedVisibility(ui.quality == CallQuality.Poor, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.Warning, null, Modifier.size(18.dp))
                        Text(stringResource(MR.string.call_poor_connection), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun CallTopBar(ui: CallUiState, center: (@Composable () -> Unit)?, actions: CallScreenActions, modifier: Modifier) {
    var codeOpen by remember(ui.callId) { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        ChromeIconButton(Icons.Default.KeyboardArrowDown, stringResource(MR.string.call_minimize), actions.minimize, Modifier.align(Alignment.TopStart))
        if (center != null) Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 60.dp)) { center() }
        ui.verificationCode?.let { code ->
            Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChromeIconButton(
                    Icons.Default.VerifiedUser,
                    stringResource(MR.string.call_verification_show),
                    { codeOpen = !codeOpen },
                    tint = MaterialTheme.colorScheme.primary,
                    selected = codeOpen,
                )
                AnimatedVisibility(codeOpen, enter = fadeIn() + scaleIn(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)), exit = fadeOut() + scaleOut()) {
                    Surface(
                        onClick = actions.onVerificationClick,
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shadowElevation = 4.dp,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(MR.string.call_verification_chip, code),
                                style = MaterialTheme.typography.titleMedium.tabular(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChromeIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    selected: Boolean = false,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else chromeColor(),
            contentColor = tint,
        ),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/** Lowest, not highest: the video-off stage is surfaceContainerHighest and chrome must stand off it. */
@Composable
private fun chromeColor() = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.9f)

private data class PillStatus(val icon: ImageVector, val text: StringResource, val warn: Boolean)

@Composable
private fun NamePill(ui: CallUiState, peerName: String, elapsed: String) {
    val status = when {
        ui.phase == CallPhase.Reconnecting -> PillStatus(Icons.Default.WifiOff, MR.string.call_status_reconnecting, warn = true)
        ui.quality == CallQuality.Poor -> PillStatus(Icons.Default.Warning, MR.string.call_poor_connection, warn = true)
        ui.phase == CallPhase.Connected && !ui.remoteShowing() -> PillStatus(Icons.Default.VideocamOff, MR.string.call_no_video, warn = false)
        else -> null
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = chromeColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    peerName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    if (ui.phase == CallPhase.Connecting) stringResource(MR.string.call_status_connecting) else elapsed,
                    style = MaterialTheme.typography.titleSmall.tabular(),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            AnimatedVisibility(status != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                if (status != null) {
                    val color = if (status.warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(
                        Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(status.icon, null, Modifier.size(16.dp), tint = color)
                        Text(stringResource(status.text), style = MaterialTheme.typography.labelLarge, color = color)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.LocalPreview(handle: VideoHandle, cameraOn: Boolean, large: Boolean) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val springSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Offset>()
    BoxWithConstraints(Modifier.matchParentSize().then(if (large) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
        val pipWidth = if (large) 192.dp else 108.dp
        val pipHeight = if (large) 132.dp else 156.dp
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val pipW = with(density) { pipWidth.toPx() }
        val pipH = with(density) { pipHeight.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        val top = with(density) { (if (large) 80.dp else PipTop).toPx() }
        val bottom = with(density) { (if (large) DesktopDockReserve else PhoneDockReserve).toPx() }
        val previewDescription = stringResource(MR.string.call_local_preview)
        val corners = listOf(
            Offset(margin, top),
            Offset(width - pipW - margin, top),
            Offset(margin, height - pipH - bottom),
            Offset(width - pipW - margin, height - pipH - bottom),
        )
        val anchor = remember(width, height) { Animatable(corners[1], Offset.VectorConverter) }
        var drag by remember(width, height) { mutableStateOf(Offset.Zero) }
        val shape = RoundedCornerShape(20.dp)
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
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
        ) {
            if (cameraOn) {
                CallVideoSurface(handle, Modifier.fillMaxSize().clip(shape), local = true)
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                ) {
                    Icon(Icons.Default.VideocamOff, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(MR.string.call_you), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** [placing] is the outgoing ring: only the controls that matter before the peer answers. */
@Composable
private fun ControlDock(ui: CallUiState, large: Boolean, actions: CallScreenActions, modifier: Modifier, placing: Boolean) {
    val showLabels = !large
    val toggles: @Composable ButtonGroupScope.() -> Unit = {
        val muteInteraction = remember { MutableInteractionSource() }
        CallToggle(
            checked = ui.muted,
            onCheckedChange = { actions.toggleMute() },
            icon = if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic,
            label = stringResource(MR.string.call_label_mute).takeIf { showLabels },
            description = stringResource(if (ui.muted) MR.string.call_unmute else MR.string.call_mute),
            state = stringResource(if (ui.muted) MR.string.call_state_muted else MR.string.call_state_mic_on),
            interaction = muteInteraction,
            modifier = Modifier.animateWidth(muteInteraction),
        )
        if (ui.availableRoutes.size > 1) {
            val routeInteraction = remember { MutableInteractionSource() }
            RouteToggle(ui, actions, showLabels, routeInteraction, Modifier.animateWidth(routeInteraction))
        }
        if (ui.videos.local != null) {
            val cameraInteraction = remember { MutableInteractionSource() }
            val off = !ui.localVideo
            CallToggle(
                checked = off,
                onCheckedChange = { actions.toggleVideo() },
                icon = if (off) Icons.Default.VideocamOff else Icons.Default.Videocam,
                label = stringResource(MR.string.call_label_camera).takeIf { showLabels },
                description = stringResource(if (off) MR.string.call_camera_on else MR.string.call_camera_off),
                state = stringResource(if (off) MR.string.call_no_video else MR.string.call_state_camera_on),
                interaction = cameraInteraction,
                modifier = Modifier.animateWidth(cameraInteraction),
            )
        }
        // Desktop webcams are single-facing, so flip only earns a slot on phone widths.
        if (ui.localVideo && !large && !placing) {
            val flipInteraction = remember { MutableInteractionSource() }
            CallAction(
                icon = Icons.Default.Cameraswitch,
                label = stringResource(MR.string.call_label_flip).takeIf { showLabels },
                description = stringResource(MR.string.call_switch_camera),
                onClick = actions.switchCamera,
                interaction = flipInteraction,
                modifier = Modifier.animateWidth(flipInteraction),
            )
        }
    }
    val dockColor = MaterialTheme.colorScheme.surfaceContainerLowest
    if (large) {
        Surface(
            modifier = modifier.padding(bottom = 28.dp),
            shape = RoundedCornerShape(40.dp),
            color = dockColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ButtonGroup(horizontalArrangement = Arrangement.spacedBy(8.dp)) { toggles() }
                VerticalDivider(Modifier.height(40.dp).padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                EndCallButton(actions.hangup, Modifier.size(width = 96.dp, height = 56.dp))
            }
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
        shape = RoundedCornerShape(40.dp),
        color = dockColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ButtonGroup(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) { toggles() }
            EndCallButton(actions.hangup, Modifier.fillMaxWidth(0.6f).height(64.dp))
        }
    }
}

private val ToggleSize = 64.dp

@Composable
private fun toggleShapes() = IconToggleButtonShapes(
    shape = CircleShape,
    pressedShape = RoundedCornerShape(14.dp),
    checkedShape = RoundedCornerShape(22.dp),
)

@Composable
private fun CallToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    label: String?,
    description: String,
    state: String,
    interaction: MutableInteractionSource,
    modifier: Modifier,
) {
    ToggleColumn(label, modifier) {
        FilledTonalIconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            shapes = toggleShapes(),
            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                checkedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                checkedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ),
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().height(ToggleSize).semantics {
                contentDescription = description
                stateDescription = state
            },
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun CallAction(
    icon: ImageVector,
    label: String?,
    description: String,
    onClick: () -> Unit,
    interaction: MutableInteractionSource,
    modifier: Modifier,
    role: Role = Role.Button,
) {
    ToggleColumn(label, modifier) {
        FilledTonalIconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(shape = CircleShape, pressedShape = RoundedCornerShape(14.dp)),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().height(ToggleSize).semantics {
                contentDescription = description
                this.role = role
            },
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun ToggleColumn(label: String?, modifier: Modifier, button: @Composable () -> Unit) {
    Column(
        modifier.width(if (label != null) 72.dp else ToggleSize),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        button()
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RouteToggle(
    ui: CallUiState,
    actions: CallScreenActions,
    showLabels: Boolean,
    interaction: MutableInteractionSource,
    modifier: Modifier,
) {
    val routes = ui.availableRoutes
    val label = stringResource(routeName(ui.route)).takeIf { showLabels }
    if (routes.size == 2) {
        val next = routes.first { it != ui.route }
        CallToggle(
            checked = ui.route != AudioRoute.Earpiece,
            onCheckedChange = { actions.setRoute(next) },
            icon = routeIcon(ui.route),
            label = label,
            description = stringResource(routeAction(next)),
            state = stringResource(routeName(ui.route)),
            interaction = interaction,
            modifier = modifier,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        CallToggle(
            checked = ui.route != AudioRoute.Earpiece,
            onCheckedChange = { expanded = true },
            icon = routeIcon(ui.route),
            label = label,
            description = stringResource(MR.string.call_route_picker),
            state = stringResource(routeName(ui.route)),
            interaction = interaction,
            modifier = Modifier,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            routes.forEach { route ->
                DropdownMenuItem(
                    text = { Text(stringResource(routeName(route))) },
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
    AudioRoute.Earpiece -> Icons.Default.PhoneInTalk
    AudioRoute.Speaker -> Icons.AutoMirrored.Filled.VolumeUp
    AudioRoute.Bluetooth -> Icons.Default.Bluetooth
    AudioRoute.Wired -> Icons.Default.Headset
}

private fun routeName(route: AudioRoute) = when (route) {
    AudioRoute.Earpiece -> MR.string.call_route_name_earpiece
    AudioRoute.Speaker -> MR.string.call_label_speaker
    AudioRoute.Bluetooth -> MR.string.call_route_name_bluetooth
    AudioRoute.Wired -> MR.string.call_route_name_wired
}

private fun routeAction(route: AudioRoute) = when (route) {
    AudioRoute.Earpiece -> MR.string.call_route_earpiece
    AudioRoute.Speaker -> MR.string.call_route_speaker
    AudioRoute.Bluetooth -> MR.string.call_route_bluetooth
    AudioRoute.Wired -> MR.string.call_route_wired
}

@Composable
private fun BoxScope.EndedLayout(
    ui: CallUiState,
    reason: CallEndReason,
    large: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    val connectedAt = ui.connectedAtMs
    val endedAt = ui.endedAtMs
    val reasonText = endedText(reason)
    val status = if (connectedAt != null && endedAt != null && endedAt > connectedAt) {
        stringResource(MR.string.call_event_duration, reasonText, formatCallDuration((endedAt - connectedAt) / 1000))
    } else reasonText
    PeerHeader(
        name = peerName,
        status = status,
        avatar = avatar,
        halo = HaloMode.None,
        dimmed = true,
        large = large,
        modifier = Modifier.align(Alignment.Center).padding(bottom = if (large) 120.dp else 170.dp),
    )
    val progress = remember(ui.callId) { Animatable(1f) }
    LaunchedEffect(ui.callId) { progress.animateTo(0f, tween(ENDED_LINGER_MS.toInt(), easing = LinearEasing)) }
    Column(
        Modifier.align(Alignment.BottomCenter).widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = if (large) 48.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = actions.retry,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
        ) {
            Icon(if (ui.isVideoCall) Icons.Default.Videocam else Icons.Default.Call, null, Modifier.size(ButtonDefaults.iconSizeFor(56.dp)))
            Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(56.dp)))
            Text(stringResource(MR.string.call_call_back), style = ButtonDefaults.textStyleFor(56.dp))
        }
        TextButton(onClick = actions.close, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(MR.string.call_close))
        }
        LinearProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.width(96.dp).padding(top = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            drawStopIndicator = {},
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
            style = (if (large) MaterialTheme.typography.displayMediumEmphasized else MaterialTheme.typography.displaySmallEmphasized)
                .copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                status,
                style = MaterialTheme.typography.titleMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        animationSpec = infiniteRepeatable(tween(if (mode == HaloMode.Ringing) 12_000 else 6_000, easing = LinearEasing)),
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
                val s = 0.84f + breath * 0.16f
                scaleX = s
                scaleY = s
                alpha = (1f - breath) * 0.26f
            }.background(ringColor, CircleShape),
        )
    }
    Box(
        Modifier.size(avatarSize * 1.26f).graphicsLayer {
            rotationZ = rotation
            val s = 0.96f + breath * 0.06f
            scaleX = s
            scaleY = s
        }.background(
            if (mode == HaloMode.Ringing) ringColor.copy(alpha = 0.22f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            cookie,
        ),
    )
}

@Composable
private fun pressMorphCorner(size: Dp, pressed: Boolean): Dp {
    val corner by animateDpAsState(if (pressed) size * 0.22f else size / 2, MaterialTheme.motionScheme.fastSpatialSpec())
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
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EndCallButton(onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner = pressMorphCorner(64.dp, pressed)
    val description = stringResource(MR.string.call_hang_up)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = HomebaseTheme.extendedColors.cameraRecord,
        contentColor = HomebaseTheme.extendedColors.onCameraRecord,
        interactionSource = interaction,
        modifier = modifier.semantics { contentDescription = description; role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CallEnd, null, Modifier.size(30.dp)) }
    }
}

@Composable
internal fun CallVideoSurface(handle: VideoHandle?, modifier: Modifier = Modifier, local: Boolean = false) {
    when (handle) {
        null -> Unit
        // Stand-in camera feed for the lab and screenshot tests: a lit backdrop and a head-and-shoulders silhouette.
        is FakeVideo -> {
            val top = if (local) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer
            val figure = if (local) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
            BoxWithConstraints(
                modifier.background(Brush.verticalGradient(listOf(top, MaterialTheme.colorScheme.surfaceContainerHighest))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = figure.copy(alpha = 0.32f),
                    modifier = Modifier.size(minOf(maxWidth, maxHeight) * 0.66f).offset(y = -maxHeight * 0.06f),
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
