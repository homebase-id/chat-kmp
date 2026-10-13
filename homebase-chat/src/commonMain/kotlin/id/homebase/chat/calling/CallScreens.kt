@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.chat.calling

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.zIndex
import id.homebase.chat.services.calling.CallDirection
import kotlin.math.roundToInt
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButtonShapes
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import id.homebase.chat.services.calling.RemoteVideoSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallQuality
import id.homebase.chat.services.calling.CallVideoView
import id.homebase.chat.services.calling.FailReason
import id.homebase.chat.services.calling.FakeVideo
import id.homebase.chat.services.calling.VideoHandle
import id.homebase.chat.services.calling.rememberScreenReaderOn
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.action_retry
import id.homebase.resources.call_accept
import id.homebase.resources.call_accept_audio_from
import id.homebase.resources.call_accept_audio_label
import id.homebase.resources.call_accept_video_from
import id.homebase.resources.call_call_back
import id.homebase.resources.call_camera_off
import id.homebase.resources.call_camera_on
import id.homebase.resources.call_decline
import id.homebase.resources.call_decline_from
import id.homebase.resources.call_ended_busy
import id.homebase.resources.call_ended_answered_elsewhere
import id.homebase.resources.call_ended_cancelled
import id.homebase.resources.call_ended_declined_elsewhere
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
import id.homebase.resources.call_label_audio_output
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
import id.homebase.resources.call_you_are_muted
import id.homebase.resources.close
import id.homebase.resources.you
import id.homebase.core.util.formatHms
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
internal const val RemoteVideoTag = "call-remote-video"
private val CompactHeight = 480.dp

/** A phone on its side: too short for the stacked phone layout, and its width must not pass it off as a desktop. */
private val LocalCompactHeight = staticCompositionLocalOf { false }

internal fun isCompactHeight(width: Dp, height: Dp): Boolean = height < CompactHeight && width > height

/** Self-view snap corners: top-start, top-end, bottom-start, bottom-end. A short window clamps the bottom pair to the top. */
internal fun previewCorners(width: Float, height: Float, pipW: Float, pipH: Float, margin: Float, top: Float, bottomReserve: Float): List<Offset> {
    val bottom = (height - pipH - bottomReserve).coerceAtLeast(top)
    val end = width - pipW - margin
    return listOf(Offset(margin, top), Offset(end, top), Offset(margin, bottom), Offset(end, bottom))
}

/** Width:height to fit the remote video at, or null to fill: a stream turned the other way from the screen is shown whole. */
internal fun remoteVideoLetterbox(video: Pair<Int, Int>?, viewWidth: Float, viewHeight: Float): Float? {
    val (w, h) = video ?: return null
    if (w <= 0 || h <= 0 || viewWidth <= 0f || viewHeight <= 0f) return null
    return if ((w > h) != (viewWidth > viewHeight)) w.toFloat() / h else null
}

private enum class HaloMode { None, Still, Ringing, Waiting }

@Composable
fun CallScreen(
    ui: CallUiState,
    peerName: String,
    avatar: @Composable (size: Dp) -> Unit,
    actions: CallScreenActions,
    modifier: Modifier = Modifier,
    chromeHeld: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    // Taking focus on a ring would turn Esc typed into the composer into a decline.
    val ringing = ui.phase == CallPhase.Incoming
    LaunchedEffect(ringing) { if (!ringing) runCatching { focus.requestFocus() } }
    val chrome = remember { CallChrome() }
    val screenReader = rememberScreenReaderOn()

    BoxWithConstraints(
        modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(chrome) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.changedToDown() }) chrome.onTouch()
                    }
                }
            }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { handleCallKey(it, ui, actions) },
    ) {
        CallBackdrop()
        val compact = isCompactHeight(maxWidth, maxHeight)
        val large = !compact && maxWidth >= LargeWidth
        val autoHide = chromeAutoHideEligible(ui, large, held = chromeHeld || chrome.holds > 0, screenReader = screenReader)
        LaunchedEffect(autoHide) { chrome.run(autoHide) }
        if (large) {
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
            CompositionLocalProvider(LocalCompactHeight provides compact, LocalCallChrome provides chrome) {
                CallStage(ui, large = false, peerName, avatar, actions, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
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
    val previewFills = ui.previewFillsStage()
    val chrome = LocalCallChrome.current
    val chromeVisible = chrome?.visible ?: true
    Box(
        Modifier.fillMaxSize().then(
            if (videoMode && chrome != null) Modifier.pointerInput(chrome) { detectTapGestures(onTap = { chrome.onTapVideo() }) } else Modifier,
        ),
    ) {
        if (videoMode) VideoStage(ui, large, avatar, chromeVisible)
        AnimatedVisibility(previewFills, Modifier.zIndex(ChromeLayer), enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        0.3f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    ),
                ),
            )
        }
        Box(contentInsets.fillMaxSize().zIndex(ChromeLayer), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.then(if (large || LocalCompactHeight.current) Modifier else Modifier.widthIn(max = ContentMaxWidth)).fillMaxSize()) {
                when (val phase = ui.phase) {
                    CallPhase.Incoming -> IncomingLayout(ui, large, peerName, avatar, actions)
                    CallPhase.Outgoing -> OutgoingLayout(ui, large, previewFills, peerName, avatar, actions)
                    is CallPhase.Ended ->
                        if (phase.reason is CallEndReason.Failed) FailedLayout(phase.reason.reason, large, peerName, avatar, actions)
                        else EndedLayout(ui, phase.reason, large, peerName, avatar, actions)
                    CallPhase.Idle -> Unit
                    else -> InCallLayout(ui, videoMode, large, peerName, avatar, actions)
                }
            }
        }
        if (videoMode || previewFills) ui.videos.local?.let { LocalPreview(it, ui.localVideo, large, chromeVisible, previewFills) }
    }
}

private const val ChromeLayer = 1f

/** Our own camera holds the stage until the call connects, then shrinks into the self-view. */
internal fun CallUiState.previewFillsStage(): Boolean =
    direction == CallDirection.Outgoing && localVideo && videos.local != null &&
        (phase == CallPhase.Outgoing || phase == CallPhase.Connecting)

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
internal fun CallPipLayout(ui: CallUiState, avatar: @Composable (Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
        if (ui.remoteShowing()) RemoteVideo(ui.videos.remote) else avatar(48.dp)
        val local = ui.videos.local
        if (local != null && ui.localVideo) {
            val width = minOf(maxWidth, maxHeight) * PipSelfFraction
            val description = stringResource(MR.string.you)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(PipSelfMargin)
                    .size(width, width * 4 / 3)
                    .clip(RoundedCornerShape(PipSelfCorner))
                    .semantics { contentDescription = description },
            ) {
                CallVideoSurface(local, Modifier.fillMaxSize(), local = true, cornerRadius = PipSelfCorner)
            }
        }
    }
}

private const val PipSelfFraction = 0.3f
private val PipSelfMargin = 6.dp
private val PipSelfCorner = 8.dp

@Composable
private fun RemoteVideo(handle: VideoHandle?) {
    val videoSize by RemoteVideoSize.current.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim), contentAlignment = Alignment.Center) {
        val letterbox = remoteVideoLetterbox(videoSize, maxWidth.value, maxHeight.value)
        CallVideoSurface(handle, (if (letterbox != null) Modifier.aspectRatio(letterbox) else Modifier.fillMaxSize()).testTag(RemoteVideoTag))
    }
}

@Composable
private fun VideoStage(ui: CallUiState, large: Boolean, avatar: @Composable (Dp) -> Unit, chromeVisible: Boolean) {
    val reconnecting = ui.phase == CallPhase.Reconnecting
    val scrimAlpha by animateFloatAsState(if (chromeVisible) 1f else 0f, MaterialTheme.motionScheme.slowEffectsSpec())
    Box(Modifier.fillMaxSize()) {
        if (ui.remoteShowing()) {
            RemoteVideo(ui.videos.remote)
            // Only backs the chrome, so it leaves with it.
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = scrimAlpha }.background(
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
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.surfaceContainerHighest,
                        1f to MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                val size = if (large) 168.dp else 152.dp
                Box(
                    Modifier.size(size * 1.5f).padding(bottom = if (large) 24.dp else 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AvatarHalo(size, if (ui.phase == CallPhase.Connecting) HaloMode.Waiting else HaloMode.Still)
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
    val compact = LocalCompactHeight.current
    PeerHeader(
        name = peerName,
        status = stringResource(if (video) MR.string.call_incoming_video else MR.string.call_incoming_voice),
        avatar = avatar,
        halo = HaloMode.Ringing,
        large = large,
        modifier = Modifier.align(Alignment.Center).padding(bottom = if (large) 120.dp else if (compact) 144.dp else 160.dp),
    )
    Row(
        Modifier.align(Alignment.BottomCenter).then(
            when {
                large -> Modifier.padding(bottom = 56.dp)
                compact -> Modifier.padding(bottom = 16.dp)
                else -> Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)
            },
        ),
        horizontalArrangement = if (large || compact) Arrangement.spacedBy(64.dp) else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        RoundCallButton(
            icon = Icons.Default.CallEnd,
            label = stringResource(MR.string.call_decline),
            description = stringResource(MR.string.call_decline_from, peerName),
            container = MaterialTheme.colorScheme.error,
            content = MaterialTheme.colorScheme.onError,
            onClick = actions.decline,
        )
        if (video) {
            RoundCallButton(
                icon = Icons.Default.Call,
                label = stringResource(MR.string.call_accept_audio_label),
                description = stringResource(MR.string.call_accept_audio_from, peerName),
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = actions.acceptAudio,
            )
        }
        RoundCallButton(
            icon = if (video) Icons.Default.Videocam else Icons.Default.Call,
            label = stringResource(MR.string.call_accept),
            description = stringResource(if (video) MR.string.call_accept_video_from else MR.string.call_accept_audio_from, peerName),
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            onClick = if (video) actions.acceptVideo else actions.acceptAudio,
            size = 84.dp,
            pulse = true,
        )
    }
}

@Composable
private fun BoxScope.OutgoingLayout(
    ui: CallUiState,
    large: Boolean,
    overPreview: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    val status = stringResource(if (ui.peerRinging) MR.string.call_status_ringing else MR.string.call_status_calling)
    if (overPreview) {
        // The face is in the middle of our own camera, so the peer moves up out of its way.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 32.dp).padding(top = if (large) 40.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!LocalCompactHeight.current) {
                avatar(if (large) 72.dp else 56.dp)
                Spacer(Modifier.height(12.dp))
            }
            Text(
                peerName,
                style = MaterialTheme.typography.headlineMediumEmphasized.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(status, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    } else {
        PeerHeader(
            name = peerName,
            status = status,
            avatar = avatar,
            halo = HaloMode.Ringing,
            large = large,
            modifier = Modifier.align(Alignment.Center).padding(bottom = if (large) 120.dp else if (LocalCompactHeight.current) 104.dp else 190.dp),
        )
    }
    ControlDock(ui, large, actions, placing = true, modifier = Modifier.align(Alignment.BottomCenter))
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
    val elapsed = rememberElapsed(ui.connectedAtMs)

    if (!videoMode) {
        Column(
            Modifier.align(Alignment.Center).padding(bottom = if (large) 110.dp else if (LocalCompactHeight.current) 104.dp else 200.dp),
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

    val chromeVisible = LocalCallChrome.current?.visible ?: true
    val enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())
    val exit = fadeOut(MaterialTheme.motionScheme.slowEffectsSpec())
    AnimatedVisibility(chromeVisible, Modifier.align(Alignment.TopCenter), enter = enter, exit = exit) {
        CallTopBar(
            ui = ui,
            center = if (videoMode) ({ NamePill(ui, peerName, elapsed) }) else null,
            actions = actions,
        )
    }
    AnimatedVisibility(chromeVisible, Modifier.align(Alignment.BottomCenter), enter = enter, exit = exit) {
        ControlDock(ui, large, actions, placing = false)
    }
}

@Composable
private fun AudioCallStatus(ui: CallUiState, elapsed: () -> String) {
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
            Text(
                elapsed(),
                style = MaterialTheme.typography.titleLargeEmphasized.copy(fontWeight = FontWeight.SemiBold).tabular(),
                color = MaterialTheme.colorScheme.primary,
            )
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
            MutedChip(ui.muted)
        }
    }
}

@Composable
private fun CallTopBar(ui: CallUiState, center: (@Composable () -> Unit)?, actions: CallScreenActions, modifier: Modifier = Modifier) {
    var codeOpen by remember(ui.callId) { mutableStateOf(false) }
    HoldCallChrome(codeOpen)
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

private const val MiddleDot = "\u00B7"

private val PreviewCorner = 20.dp

private data class PillStatus(val icon: ImageVector, val text: StringResource, val warn: Boolean)

@Composable
private fun NamePill(ui: CallUiState, peerName: String, elapsed: () -> String) {
    val status = when {
        ui.quality == CallQuality.Poor -> PillStatus(Icons.Default.Warning, MR.string.call_poor_connection, warn = true)
        ui.phase == CallPhase.Connected && !ui.remoteShowing() -> PillStatus(Icons.Default.VideocamOff, MR.string.call_no_video, warn = false)
        else -> null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = chromeColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                Modifier.heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    peerName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (ui.phase == CallPhase.Connecting) stringResource(MR.string.call_status_connecting) else elapsed(),
                        style = MaterialTheme.typography.labelLarge.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    AnimatedVisibility(status != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        if (status != null) {
                            val color = if (status.warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(MiddleDot, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(status.icon, null, Modifier.size(16.dp), tint = color)
                                Text(stringResource(status.text), style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        MutedChip(ui.muted)
    }
}

/** Mirrors the mute toggle away from the dock, where a glance at the peer will catch it. */
@Composable
private fun MutedChip(muted: Boolean) {
    AnimatedVisibility(
        muted,
        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            Row(
                Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.MicOff, null, Modifier.size(16.dp))
                Text(stringResource(MR.string.call_you_are_muted), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun BoxScope.LocalPreview(handle: VideoHandle, cameraOn: Boolean, large: Boolean, chromeVisible: Boolean, fillStage: Boolean) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val rtl = direction == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val springSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Offset>()
    val fill = remember { Animatable(if (fillStage) 1f else 0f) }
    LaunchedEffect(fillStage) { fill.animateTo(if (fillStage) 1f else 0f, tween(PreviewShrinkMs, easing = EmphasizedEasing)) }
    val filling = fill.value > 0f
    // Below the chrome while it fills the stage; the tile sits above it.
    BoxWithConstraints(Modifier.matchParentSize().zIndex(if (filling) ChromeLayer / 2 else ChromeLayer * 2)) {
        val compact = LocalCompactHeight.current
        // A phone on its side captures landscape frames, so the inset turns with it.
        val pipWidth = if (large) 192.dp else if (compact) 156.dp else 108.dp
        val pipHeight = if (large) 132.dp else if (compact) 108.dp else 156.dp
        val insets = if (large) WindowInsets(0) else WindowInsets.safeDrawing
        val insetStart = (if (rtl) insets.getRight(density, direction) else insets.getLeft(density, direction)).toFloat()
        val insetTop = insets.getTop(density).toFloat()
        val insetEnd = (if (rtl) insets.getLeft(density, direction) else insets.getRight(density, direction)).toFloat()
        val insetBottom = insets.getBottom(density).toFloat()
        val stageW = with(density) { maxWidth.toPx() }
        val stageH = with(density) { maxHeight.toPx() }
        val width = stageW - insetStart - insetEnd
        val height = stageH - insetTop - insetBottom
        val pipW = with(density) { pipWidth.toPx() }
        val pipH = with(density) { pipHeight.toPx() }
        val reserve = previewReserve(chromeVisible, large, compact)
        val margin = with(density) { PreviewMargin.toPx() }
        val top = with(density) { reserve.top.toPx() }
        val bottom = with(density) { reserve.bottom.toPx() }
        val previewDescription = stringResource(MR.string.call_local_preview)
        val corners = previewCorners(width, height, pipW, pipH, margin, top, bottom)
        var corner by remember { mutableIntStateOf(1) }
        val anchor = remember(width, height, compact) { Animatable(corners[corner], Offset.VectorConverter) }
        var drag by remember(width, height, compact) { mutableStateOf(Offset.Zero) }
        LaunchedEffect(corners[corner]) { anchor.animateTo(corners[corner], springSpec) }
        val shape = RoundedCornerShape(PreviewCorner)
        Surface(
            modifier = Modifier
                .offset { lerp(anchor.value + drag + Offset(insetStart, insetTop), Offset.Zero, fill.value).round() }
                .layout { measurable, _ ->
                    val w = (pipW + (stageW - pipW) * fill.value).roundToInt()
                    val h = (pipH + (stageH - pipH) * fill.value).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                }
                .semantics { contentDescription = previewDescription }
                .then(
                    if (fillStage) Modifier else Modifier.pointerInput(width, height, rtl, compact) {
                        detectDragGestures(
                            onDrag = { change, amount ->
                                change.consume()
                                drag += if (rtl) Offset(-amount.x, amount.y) else amount
                            },
                            onDragEnd = {
                                val landed = anchor.value + drag
                                val center = landed + Offset(pipW / 2, pipH / 2)
                                corner = corners.indices.minBy { (corners[it] + Offset(pipW / 2, pipH / 2) - center).getDistance() }
                                drag = Offset.Zero
                                val target = corners[corner]
                                scope.launch {
                                    anchor.snapTo(landed)
                                    anchor.animateTo(target, springSpec)
                                }
                            },
                        )
                    },
                ),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = if (filling) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = if (filling) 0.dp else 8.dp,
        ) {
            if (cameraOn) {
                // The radius stays fixed: on Android a change between zero and non-zero swaps the renderer.
                CallVideoSurface(handle, Modifier.fillMaxSize().clip(shape), local = true, cornerRadius = PreviewCorner)
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                ) {
                    Icon(Icons.Default.VideocamOff, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(MR.string.you), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private const val PreviewShrinkMs = 400
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** [placing] is the outgoing ring: only the controls that matter before the peer answers. */
@Composable
private fun ControlDock(ui: CallUiState, large: Boolean, actions: CallScreenActions, placing: Boolean, modifier: Modifier = Modifier) {
    val compact = LocalCompactHeight.current
    val showLabels = !large && !compact
    val showRoute = ui.availableRoutes.size > 1
    val showCamera = ui.videos.local != null
    // Desktop webcams are single-facing, so flip only earns a slot on phone widths.
    val showFlip = ui.localVideo && !large && !placing
    val count = 1 + listOf(showRoute, showCamera, showFlip).count { it }
    val routeSlot = 1
    val cameraSlot = if (showRoute) 2 else 1
    val flipSlot = cameraSlot + if (showCamera) 1 else 0
    val toggles: @Composable ButtonGroupScope.() -> Unit = {
        val muteInteraction = remember { MutableInteractionSource() }
        CallToggle(
            checked = ui.muted,
            onCheckedChange = { actions.toggleMute() },
            icon = if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic,
            label = stringResource(MR.string.call_label_mute).takeIf { showLabels },
            description = stringResource(if (ui.muted) MR.string.call_unmute else MR.string.call_mute),
            state = stringResource(if (ui.muted) MR.string.call_state_muted else MR.string.call_state_mic_on),
            shapes = segmentShapes(0, count),
            interaction = muteInteraction,
            modifier = Modifier.animateWidth(muteInteraction),
        )
        if (showRoute) {
            val routeInteraction = remember { MutableInteractionSource() }
            RouteToggle(ui, actions, showLabels, segmentShapes(routeSlot, count), routeInteraction, Modifier.animateWidth(routeInteraction))
        }
        if (showCamera) {
            val cameraInteraction = remember { MutableInteractionSource() }
            val off = !ui.localVideo
            CallToggle(
                checked = off,
                onCheckedChange = { actions.toggleVideo() },
                icon = if (off) Icons.Default.VideocamOff else Icons.Default.Videocam,
                label = stringResource(MR.string.call_label_camera).takeIf { showLabels },
                description = stringResource(if (off) MR.string.call_camera_on else MR.string.call_camera_off),
                state = stringResource(if (off) MR.string.call_no_video else MR.string.call_state_camera_on),
                shapes = segmentShapes(cameraSlot, count),
                interaction = cameraInteraction,
                modifier = Modifier.animateWidth(cameraInteraction),
            )
        }
        if (showFlip) {
            val flipInteraction = remember { MutableInteractionSource() }
            CallAction(
                icon = Icons.Default.Cameraswitch,
                label = stringResource(MR.string.call_label_flip).takeIf { showLabels },
                description = stringResource(MR.string.call_switch_camera),
                onClick = actions.switchCamera,
                shapes = segmentShapes(flipSlot, count),
                interaction = flipInteraction,
                modifier = Modifier.animateWidth(flipInteraction),
            )
        }
    }
    val dockColor = MaterialTheme.colorScheme.surfaceContainerLowest
    if (large || compact) {
        Surface(
            modifier = modifier.padding(bottom = if (compact) 12.dp else 28.dp),
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
                ButtonGroup(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) { toggles() }
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
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween, Alignment.CenterHorizontally),
            ) { toggles() }
            EndCallButton(actions.hangup, Modifier.fillMaxWidth(0.6f).height(64.dp))
        }
    }
}

private val ToggleSize = 64.dp

/** M3 Expressive connected group: round outer ends, tight inner joins, and a checked toggle morphs to a full pill. */
@Composable
private fun segmentShapes(index: Int, count: Int): IconToggleButtonShapes {
    val shapes = when {
        count == 1 -> return IconToggleButtonShapes(CircleShape, RoundedCornerShape(16.dp), CircleShape)
        index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }
    return IconToggleButtonShapes(shapes.shape, shapes.pressedShape, shapes.checkedShape)
}

@Composable
private fun CallToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    label: String?,
    description: String,
    state: String,
    shapes: IconToggleButtonShapes,
    interaction: MutableInteractionSource,
    modifier: Modifier,
) {
    ToggleColumn(label, modifier) {
        FilledTonalIconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            shapes = shapes,
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
    shapes: IconToggleButtonShapes,
    interaction: MutableInteractionSource,
    modifier: Modifier,
    role: Role = Role.Button,
) {
    ToggleColumn(label, modifier) {
        FilledTonalIconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(shape = shapes.shape, pressedShape = shapes.pressedShape),
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
    shapes: IconToggleButtonShapes,
    interaction: MutableInteractionSource,
    modifier: Modifier,
) {
    val routes = ui.availableRoutes
    // Labels name the control, never the current route: the checked shape and the state description carry that.
    val speakerToggle = routes.size == 2 && AudioRoute.Speaker in routes
    val label = stringResource(if (speakerToggle) MR.string.call_label_speaker else MR.string.call_label_audio_output).takeIf { showLabels }
    if (routes.size == 2) {
        val next = routes.first { it != ui.route }
        CallToggle(
            checked = if (speakerToggle) ui.route == AudioRoute.Speaker else ui.route != AudioRoute.Earpiece,
            onCheckedChange = { actions.setRoute(next) },
            icon = if (speakerToggle) Icons.AutoMirrored.Filled.VolumeUp else routeIcon(ui.route),
            label = label,
            description = stringResource(routeAction(next)),
            state = stringResource(routeName(ui.route)),
            shapes = shapes,
            interaction = interaction,
            modifier = modifier,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    HoldCallChrome(expanded)
    Box(modifier) {
        CallToggle(
            checked = ui.route != AudioRoute.Earpiece,
            onCheckedChange = { expanded = true },
            icon = routeIcon(ui.route),
            label = label,
            description = stringResource(MR.string.call_route_picker),
            state = stringResource(routeName(ui.route)),
            shapes = shapes,
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
        stringResource(MR.string.call_event_duration, reasonText, spokenCallDuration((endedAt - connectedAt) / 1000))
    } else reasonText
    PeerHeader(
        name = peerName,
        status = status,
        avatar = avatar,
        halo = HaloMode.None,
        dimmed = true,
        large = large,
        modifier = Modifier.align(Alignment.Center).padding(bottom = if (large) 120.dp else if (LocalCompactHeight.current) 80.dp else 170.dp),
    )
    EndActions(large, spacing = 12.dp) { slot ->
        RetryButton(
            icon = if (ui.placedWithVideo) Icons.Default.Videocam else Icons.Default.Call,
            text = stringResource(MR.string.call_call_back),
            onClick = actions.retry,
            modifier = slot,
        )
        CloseButton(onClick = actions.close, modifier = slot)
    }
}

@Composable
private fun RetryButton(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
    ) {
        Icon(icon, null, Modifier.size(ButtonDefaults.iconSizeFor(56.dp)))
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(56.dp)))
        Text(text, style = ButtonDefaults.textStyleFor(56.dp))
    }
}

/** Stacked under the header in portrait; side by side on a phone in landscape, where a stack would not fit. */
@Composable
private fun BoxScope.EndActions(large: Boolean, spacing: Dp, content: @Composable (slot: Modifier) -> Unit) {
    if (LocalCompactHeight.current) {
        Row(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) { content(Modifier.weight(1f)) }
        return
    }
    Column(
        Modifier.align(Alignment.BottomCenter).widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = if (large) 48.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) { content(Modifier.fillMaxWidth()) }
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
    ) {
        Text(stringResource(MR.string.close), style = ButtonDefaults.textStyleFor(56.dp))
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
    val (titleRes, bodyRes, badge) = when (reason) {
        FailReason.NoRoute -> Triple(MR.string.call_failed_title, MR.string.call_failed_body, Icons.Default.WifiOff)
        FailReason.Permission -> Triple(MR.string.call_permission_title, MR.string.call_permission_body, Icons.Default.MicOff)
        FailReason.Other -> Triple(MR.string.call_ended_failed, MR.string.call_failed_other_body, Icons.Default.ErrorOutline)
    }
    val title = stringResource(titleRes)
    val body = stringResource(bodyRes)
    val compact = LocalCompactHeight.current
    Column(
        Modifier.align(Alignment.Center).widthIn(max = 420.dp).padding(horizontal = 32.dp)
            .padding(bottom = if (large) 72.dp else if (compact) 80.dp else 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(if (compact) 88.dp else 132.dp)) {
            Box(Modifier.align(Alignment.Center).alpha(0.6f)) { avatar(if (compact) 72.dp else 112.dp) }
            val badgeShape = MaterialShapes.Cookie9Sided.toShape()
            Box(
                Modifier.align(Alignment.BottomEnd).size(if (compact) 40.dp else 56.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, badgeShape)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, badgeShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(badge, null, Modifier.size(if (compact) 20.dp else 26.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Spacer(Modifier.height(if (compact) 8.dp else 20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMediumEmphasized.copy(fontWeight = FontWeight.SemiBold),
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
    EndActions(large, spacing = 8.dp) { slot ->
        RetryButton(Icons.Default.Refresh, stringResource(MR.string.action_retry), actions.retry, slot)
        TextButton(
            onClick = actions.close,
            modifier = slot.heightIn(min = 56.dp),
            contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
        ) {
            Text(stringResource(MR.string.close), style = ButtonDefaults.textStyleFor(56.dp))
        }
    }
}

@Composable
fun OngoingCallPill(ui: CallUiState, peerName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val elapsed = rememberElapsed(ui.connectedAtMs)
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
                stringResource(MR.string.call_pill_text, peerName, elapsed()),
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
        CallEndReason.AnsweredElsewhere -> MR.string.call_ended_answered_elsewhere
        CallEndReason.DeclinedElsewhere -> MR.string.call_ended_declined_elsewhere
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
    val compact = LocalCompactHeight.current
    val avatarSize = if (large) 152.dp else if (compact) 72.dp else 136.dp
    val avatarAlpha by animateFloatAsState(if (dimmed) 0.55f else 1f, MaterialTheme.motionScheme.defaultEffectsSpec())
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(avatarSize * 1.5f), contentAlignment = Alignment.Center) {
            if (halo != HaloMode.None) AvatarHalo(avatarSize, halo)
            Box(Modifier.alpha(avatarAlpha)) { avatar(avatarSize) }
        }
        Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
        Text(
            name,
            style = when {
                large -> MaterialTheme.typography.displayMediumEmphasized
                compact -> MaterialTheme.typography.headlineMediumEmphasized
                else -> MaterialTheme.typography.displaySmallEmphasized
            }.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 1 else 3,
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
    val cookie = MaterialShapes.Cookie12Sided.toShape()
    val ringColor = MaterialTheme.colorScheme.primary
    if (mode == HaloMode.Still) {
        Box(Modifier.size(avatarSize * 1.26f).background(ringColor.copy(alpha = 0.2f), cookie))
        return
    }
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
        }.background(ringColor.copy(alpha = 0.2f), cookie),
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
    pulse: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner = pressMorphCorner(size, pressed)
    Column(
        Modifier.widthIn(min = 88.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PulseOverflow + 4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (pulse) AcceptPulse(size, container)
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(corner),
                color = container,
                contentColor = content,
                interactionSource = interaction,
                modifier = Modifier.size(size).semantics { contentDescription = description; role = Role.Button },
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(if (size > 72.dp) 36.dp else 32.dp)) }
            }
        }
        // The button's description already says it; a second node would be announced twice.
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

private val PulseOverflow = 12.dp

/** Grows by at most [PulseOverflow] per side so the halo never reaches the label below. */
@Composable
private fun AcceptPulse(size: Dp, color: Color) {
    val transition = rememberInfiniteTransition(label = "acceptPulse")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "acceptPulseT")
    Box(
        Modifier.size(size).graphicsLayer {
            val s = 1f + t * (PulseOverflow * 2 / size)
            scaleX = s
            scaleY = s
            alpha = (1f - t) * 0.4f
        }.background(color, CircleShape),
    )
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
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        interactionSource = interaction,
        modifier = modifier.semantics { contentDescription = description; role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CallEnd, null, Modifier.size(30.dp)) }
    }
}

@Composable
internal fun CallVideoSurface(handle: VideoHandle?, modifier: Modifier = Modifier, local: Boolean = false, cornerRadius: Dp = 0.dp) {
    when (handle) {
        null -> Unit
        // Stand-in camera feed for the lab and screenshot tests, tinted from the theme so either side stays legible.
        is FakeVideo -> {
            val scheme = MaterialTheme.colorScheme
            val (frame, ink) = if (local) scheme.primaryContainer to scheme.onPrimaryContainer else scheme.tertiaryContainer to scheme.onTertiaryContainer
            BoxWithConstraints(
                modifier.background(Brush.verticalGradient(listOf(frame, lerp(frame, ink, 0.18f)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    handle.initials,
                    style = MaterialTheme.typography.displayLargeEmphasized.copy(fontSize = (minOf(maxWidth, maxHeight).value * 0.3f).sp),
                    color = ink.copy(alpha = 0.72f),
                    modifier = Modifier.offset(y = -maxHeight * 0.04f),
                )
            }
        }
        else -> CallVideoView(handle, modifier, cornerRadius)
    }
}

/** Read the returned text only where it is drawn, so the per-second tick recomposes that text and nothing around it. */
@Composable
private fun rememberElapsed(connectedAtMs: Long?): () -> String {
    val now = remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(connectedAtMs) {
        if (connectedAtMs == null) return@LaunchedEffect
        while (true) {
            now.longValue = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    return remember(connectedAtMs) { { formatHms(now.longValue - (connectedAtMs ?: now.longValue), padMinutes = true) } }
}
