@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.homebase.chat.calling

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.gestures.detectTapGestures
import id.homebase.resources.call_mini_mode
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
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import id.homebase.resources.call_device_current
import id.homebase.resources.call_device_system_default
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.runtime.saveable.rememberSaveable
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
import id.homebase.chat.services.calling.MediaDeviceKind
import id.homebase.chat.services.calling.VideoHandle
import id.homebase.chat.services.calling.rememberScreenReaderOn
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.call_devices
import id.homebase.resources.call_device_menu_audio
import id.homebase.resources.call_device_menu_camera
import id.homebase.resources.call_state_mic_on_device
import id.homebase.resources.action_retry
import id.homebase.resources.call_accept
import id.homebase.resources.call_accept_audio_from
import id.homebase.resources.call_accept_audio_label
import id.homebase.resources.call_accept_video_from
import id.homebase.resources.call_back_to_chat
import id.homebase.resources.call_hide_self_view
import id.homebase.resources.call_key_escape
import id.homebase.resources.call_show_self_view
import id.homebase.resources.call_swap_views
import id.homebase.resources.call_tooltip_shortcut
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
import id.homebase.resources.call_bar_end
import id.homebase.resources.call_bar_return
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
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
    val selectDevice: (MediaDeviceKind, String) -> Unit = { _, _ -> },
    val toggleMini: (() -> Unit)? = null,
)

private val ContentMaxWidth = 480.dp
private val LargeWidth = 840.dp
private val PipTop = 84.dp
internal const val RemoteVideoTag = "call-remote-video"
private val CompactHeight = 480.dp
internal val MinimalWidth = 480.dp
internal val MinimalHeight = 360.dp
private val PhoneDockReserve = 236.dp
private val DesktopDockReserve = 128.dp
private val CompactDockReserve = 112.dp
private val CompactPipTop = 72.dp

/** A phone on its side: too short for the stacked phone layout, and its width must not pass it off as a desktop. */
private val LocalCompactHeight = staticCompositionLocalOf { false }

internal fun isCompactHeight(width: Dp, height: Dp): Boolean = height < CompactHeight && width > height

internal enum class DesktopTier { Minimal, Compact, Medium, Expanded }

/** Set only inside a dedicated desktop call window; null on phones and in the in-app overlay. */
internal val LocalDesktopTier = staticCompositionLocalOf<DesktopTier?> { null }

internal val LocalMicLevel = staticCompositionLocalOf<StateFlow<Float>> { NoMicLevel }

private val NoMicLevel: StateFlow<Float> = MutableStateFlow(0f)

/** Which dock device menu is open, hoisted so a snackbar's "Change" can open the mic menu at its own chevron. */
private val LocalDeviceMenuRequest = staticCompositionLocalOf<MutableState<MediaDeviceKind?>> { error("provided by CallScreen") }

internal fun desktopTier(width: Dp, height: Dp): DesktopTier = when {
    width < MinimalWidth || height < MinimalHeight -> DesktopTier.Minimal
    width < 600.dp || height < CompactHeight -> DesktopTier.Compact
    width < LargeWidth -> DesktopTier.Medium
    else -> DesktopTier.Expanded
}


/** Self-view snap corners: top-start, top-end, bottom-start, bottom-end. A short window clamps the bottom pair to the top. */
internal fun previewCorners(width: Float, height: Float, pipW: Float, pipH: Float, margin: Float, top: Float, bottomReserve: Float): List<Offset> =
    snapCorners(Rect(margin, top, width - margin, height - bottomReserve), Size(pipW, pipH))

internal fun nearestPreviewCorner(corners: List<Offset>, landed: Offset): Offset = corners.minBy { (it - landed).getDistance() }

/** Width:height to fit the remote video at, or null to fill: a stream turned the other way from the screen is shown whole. */
internal fun remoteVideoLetterbox(video: Pair<Int, Int>?, viewWidth: Float, viewHeight: Float): Float? {
    val (w, h) = video ?: return null
    if (w <= 0 || h <= 0 || viewWidth <= 0f || viewHeight <= 0f) return null
    return if ((w > h) != (viewWidth > viewHeight)) w.toFloat() / h else null
}

internal enum class HaloMode { None, Still, Ringing, Waiting }

@Composable
fun CallScreen(
    ui: CallUiState,
    peerName: String,
    avatar: @Composable (size: Dp) -> Unit,
    actions: CallScreenActions,
    modifier: Modifier = Modifier,
    fillsWindow: Boolean = false,
    topInset: Dp = 0.dp,
    deviceMenu: MutableState<MediaDeviceKind?> = remember { mutableStateOf(null) },
    hold: ChromeHold = remember { ChromeHold() },
    chromeHeld: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    var showShortcuts by remember { mutableStateOf(false) }
    val activity = remember { Channel<Unit>(Channel.CONFLATED) }
    val gate = LocalShortcutGate.current
    val autoHide = fillsWindow && chromeAutoHideEligible(ui)
    val held = hold.count > 0 || deviceMenu.value != null || showShortcuts
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(autoHide, held) {
        idle = false
        if (!autoHide || held) return@LaunchedEffect
        activity.tryReceive()
        // Not withTimeoutOrNull: its timer bypasses the test clock, so the fade could never be exercised in a test.
        activity.receiveAsFlow().onStart { emit(Unit) }.collectLatest {
            idle = false
            delay(ChromeIdleMs)
            idle = true
        }
    }
    // Taking focus on a ring would turn Esc typed into the composer into a decline.
    val ringing = ui.phase == CallPhase.Incoming
    LaunchedEffect(ringing) { if (!ringing) runCatching { focus.requestFocus() } }
    val chrome = remember { CallChromeState() }
    val screenReader = rememberScreenReaderOn()

    CompositionLocalProvider(
        LocalDeviceMenuRequest provides deviceMenu,
        LocalChromeHold provides hold,
        LocalChromeVisible provides if (fillsWindow) !idle else chrome.visible,
    ) {
    BoxWithConstraints(
        modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var last: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val at = event.changes.firstOrNull()?.position
                        // Compose re-sends a move at a resting pointer when the layers under it change, such as the fading scrim.
                        val resting = event.type == PointerEventType.Move && at == last
                        last = at
                        if (!resting) activity.trySend(Unit)
                    }
                }
            }
            .pointerInput(chrome) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.changedToDown() }) chrome.onTouch()
                    }
                }
            }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent {
                activity.trySend(Unit)
                handleCallKey(it, ui, actions, showShortcuts = if (fillsWindow) ({ showShortcuts = true }) else null, gate = gate)
            },
    ) {
        if (showShortcuts) CallShortcutsDialog { showShortcuts = false }
        CallBackdrop()
        val compact = isCompactHeight(maxWidth, maxHeight)
        val chromeInsets = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = topInset)
        val large = !compact && maxWidth >= LargeWidth
        val autoHide = !fillsWindow && chromeAutoHideEligible(ui, large, held = chromeHeld || chrome.holds > 0, screenReader = screenReader)
        LaunchedEffect(autoHide) { chrome.run(autoHide) }
        // A dedicated call window is already the frame, so no card inside it.
        if (fillsWindow) {
            CompositionLocalProvider(LocalDesktopTier provides desktopTier(maxWidth, maxHeight), LocalCompactHeight provides compact) {
                CallStage(ui, large = true, peerName, avatar, actions, chromeInsets)
            }
        } else {
            CompositionLocalProvider(LocalCompactHeight provides compact, LocalCallChrome provides chrome) {
                CallStage(ui, large = large, peerName, avatar, actions, chromeInsets)
            }
        }
    }
    }
}

internal fun handleCallKey(
    event: KeyEvent,
    ui: CallUiState,
    actions: CallScreenActions,
    textFocused: Boolean = false,
    showShortcuts: (() -> Unit)? = null,
    gate: ShortcutGate? = null,
): Boolean {
    val shortcut = callShortcutFor(event.toCallKeyInput(), ui, textFocused) ?: return false
    if (gate != null && !gate.accept(shortcut)) return true
    when (shortcut) {
        CallShortcut.ToggleMute -> actions.toggleMute()
        CallShortcut.ToggleCamera -> actions.toggleVideo()
        CallShortcut.ToggleMini -> (actions.toggleMini ?: return false)()
        CallShortcut.HangUp -> actions.hangup()
        CallShortcut.Close -> actions.endFromKeyboard()
        CallShortcut.ShowShortcuts -> (showShortcuts ?: return false)()
    }
    return true
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
    var swapRequested by rememberSaveable { mutableStateOf(false) }
    val local = ui.videos.local
    val onSwap = remember { { swapRequested = !swapRequested } }
    val swapped = swapRequested && videoMode && ui.remoteShowing() && ui.localVideo && local != null
    val chrome = LocalCallChrome.current
    val chromeVisible = chrome?.visible ?: true
    Box(
        Modifier.fillMaxSize().then(
            if (videoMode && chrome != null) Modifier.pointerInput(chrome) { detectTapGestures(onTap = { chrome.onTapVideo() }) } else Modifier,
        ),
    ) {
        if (videoMode) VideoStage(ui, large, avatar, chromeVisible)
        if (swapped && local != null) SelfStage(local)
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
                    CallPhase.Outgoing -> OutgoingLayout(ui, large, peerName, avatar, actions, onVideo = previewFills)
                    is CallPhase.Ended ->
                        if (phase.reason is CallEndReason.Failed) FailedLayout(phase.reason.reason, large, peerName, avatar, actions)
                        else EndedLayout(ui, phase.reason, large, peerName, avatar, actions)
                    CallPhase.Idle -> Unit
                    else -> InCallLayout(ui, videoMode, large, chromeVisible, peerName, avatar, actions)
                }
            }
        }
        if (videoMode || previewFills) local?.let { LocalPreview(it, ui.localVideo, large, if (swapped) ui.videos.remote else null, onSwap, previewFills, chromeVisible) }
    }
}

// Eased stops: two linear ramps ending at hard stops read as bands across a flat video frame.
@Composable
private fun ChromeScrim() {
    val scrim = MaterialTheme.colorScheme.scrim
    val alpha by animateFloatAsState(if (LocalChromeVisible.current) 1f else 0f, tween(250), label = "chromeScrim")
    Box(
        Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(
            Brush.verticalGradient(
                0f to scrim.copy(alpha = 0.40f),
                0.08f to scrim.copy(alpha = 0.26f),
                0.18f to scrim.copy(alpha = 0.08f),
                0.28f to Color.Transparent,
                0.62f to Color.Transparent,
                0.76f to scrim.copy(alpha = 0.10f),
                0.9f to scrim.copy(alpha = 0.32f),
                1f to scrim.copy(alpha = 0.46f),
            ),
        ),
    )
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
            // Two tonal stops from one palette: a third stop drew a visible band where the slopes met.
            Brush.verticalGradient(
                0f to MaterialTheme.colorScheme.surfaceContainerHigh,
                1f to MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ),
    )
}

@Composable
private fun SelfStage(handle: VideoHandle) {
    val surface = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxSize().testTag(SelfStageTag)) {
        CallVideoSurface(handle, Modifier.fillMaxSize(), local = true)
        // Surface-tinted, not scrim: the chrome keeps its theme colours over the picture in light and dark alike.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to surface.copy(alpha = 0.55f),
                    0.22f to surface.copy(alpha = 0.12f),
                    0.7f to surface.copy(alpha = 0.12f),
                    1f to surface.copy(alpha = 0.6f),
                ),
            ),
        )
    }
}

internal const val SelfStageTag = "call-self-stage"

internal fun CallUiState.remoteShowing(): Boolean =
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
internal fun RemoteVideo(handle: VideoHandle?) {
    val videoSize by RemoteVideoSize.current.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim), contentAlignment = Alignment.Center) {
        val letterbox = remoteVideoLetterbox(videoSize, maxWidth.value, maxHeight.value)
        CallVideoSurface(handle, (if (letterbox != null) Modifier.aspectRatio(letterbox) else Modifier.fillMaxSize()).testTag(RemoteVideoTag))
    }
}

@Composable
private fun VideoStage(ui: CallUiState, large: Boolean, avatar: @Composable (Dp) -> Unit, chromeVisible: Boolean) {
    val reconnecting = ui.phase == CallPhase.Reconnecting
    Box(Modifier.fillMaxSize()) {
        if (ui.remoteShowing()) {
            RemoteVideo(ui.videos.remote)
            ChromeScrim()
        } else {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh).clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                // The peer's own photo, blurred to colour, so a camera-off call still feels like that person.
                val backdrop = maxOf(maxWidth, maxHeight) * 1.1f
                Box(Modifier.requiredSize(backdrop).blur(96.dp, BlurredEdgeTreatment.Unbounded).alpha(0.45f)) {
                    avatar(backdrop)
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)))
                val tier = LocalDesktopTier.current
                val size = when (tier) {
                    DesktopTier.Minimal -> 88.dp
                    DesktopTier.Compact -> 128.dp
                    DesktopTier.Medium -> 168.dp
                    else -> if (large) 208.dp else 152.dp
                }
                Column(
                    Modifier.padding(bottom = if (tier == DesktopTier.Minimal) 48.dp else if (large) 40.dp else 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(size * 1.3f), contentAlignment = Alignment.Center) {
                        AvatarHalo(size, if (ui.phase == CallPhase.Connecting) HaloMode.Waiting else HaloMode.Still)
                        avatar(size)
                    }
                    if (ui.phase == CallPhase.Connected && tier != DesktopTier.Minimal) {
                        Spacer(Modifier.height(12.dp))
                        Surface(shape = CircleShape, color = chromeColor(), contentColor = MaterialTheme.colorScheme.onSurface) {
                            Row(
                                Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.VideocamOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(MR.string.call_no_video), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(reconnecting, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                // A desktop window says it in the name pill instead, clear of the avatar and the self view.
                if (LocalDesktopTier.current == null) Surface(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
        horizontalArrangement = when {
            LocalDesktopTier.current == DesktopTier.Compact -> Arrangement.spacedBy(16.dp)
            large || compact -> Arrangement.spacedBy(64.dp)
            else -> Arrangement.SpaceEvenly
        },
        verticalAlignment = Alignment.Bottom,
    ) {
        RoundCallButton(
            icon = Icons.Default.CallEnd,
            label = stringResource(MR.string.call_decline),
            description = stringResource(MR.string.call_decline_from, peerName),
            container = endCallContainer(),
            content = endCallContent(),
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
            size = if (LocalDesktopTier.current != null) 72.dp else 84.dp,
            pulse = true,
        )
    }
}

@Composable
private fun BoxScope.OutgoingLayout(
    ui: CallUiState,
    large: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
    onVideo: Boolean = false,
) {
    val status = stringResource(if (ui.peerRinging) MR.string.call_status_ringing else MR.string.call_status_calling)
    if (onVideo) {
        // A narrow window has no room beside the top-end buttons for a centred card.
        val narrow = LocalDesktopTier.current.let { it == DesktopTier.Minimal || it == DesktopTier.Compact }
        RingingCard(
            peerName, status, avatar,
            if (narrow) Modifier.align(Alignment.TopStart).padding(top = 4.dp, end = 104.dp)
            else Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
        )
    } else PeerHeader(
        name = peerName,
        status = status,
        avatar = avatar,
        halo = HaloMode.Ringing,
        large = large,
        modifier = Modifier.align(Alignment.Center).padding(bottom = if (LocalDesktopTier.current == DesktopTier.Minimal) 56.dp else if (large) 120.dp else if (LocalCompactHeight.current) 104.dp else 190.dp),
    )
    if (LocalDesktopTier.current != null) CallTopBar(ui, center = null, actions = actions, modifier = Modifier.align(Alignment.TopCenter))
    ControlDock(ui, large, actions, Modifier.align(Alignment.BottomCenter), placing = true)
}

/** Who you are calling, kept compact and on a card so your own camera stays the stage while it rings. */
@Composable
private fun RingingCard(peerName: String, status: String, avatar: @Composable (Dp) -> Unit, modifier: Modifier) {
    val minimal = LocalDesktopTier.current == DesktopTier.Minimal
    Surface(
        modifier = modifier.padding(horizontal = 12.dp).semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(32.dp),
        color = chromeColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 24.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val size = if (minimal) 40.dp else 56.dp
            Box(Modifier.size(size * 1.3f), contentAlignment = Alignment.Center) {
                AvatarHalo(size, HaloMode.Ringing)
                avatar(size)
            }
            Column {
                Text(
                    peerName,
                    style = (if (minimal) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLargeEmphasized).copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.InCallLayout(
    ui: CallUiState,
    videoMode: Boolean,
    large: Boolean,
    chromeVisible: Boolean,
    peerName: String,
    avatar: @Composable (Dp) -> Unit,
    actions: CallScreenActions,
) {
    val reconnecting = ui.phase == CallPhase.Reconnecting
    val connecting = ui.phase == CallPhase.Connecting
    val elapsed = rememberElapsedText(ui.connectedAtMs)

    if (!videoMode) {
        Column(
            Modifier.align(Alignment.Center).padding(bottom = if (LocalDesktopTier.current == DesktopTier.Minimal) 56.dp else if (large) 110.dp else if (LocalCompactHeight.current) 104.dp else 200.dp),
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
private fun AudioCallStatus(ui: CallUiState, elapsed: String) {
    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { AudioCallStatusContent(ui, elapsed) }
}

@Composable
private fun AudioCallStatusContent(ui: CallUiState, elapsed: String) {
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
                elapsed,
                style = (if (LocalDesktopTier.current == DesktopTier.Expanded) MaterialTheme.typography.headlineSmallEmphasized else MaterialTheme.typography.titleLargeEmphasized)
                    .copy(fontWeight = FontWeight.SemiBold).tabular(),
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
    HoldChromeWhile(codeOpen)
    HoldCallChrome(codeOpen)
    val desktop = LocalDesktopTier.current != null
    val minimal = LocalDesktopTier.current == DesktopTier.Minimal
    val mini = actions.toggleMini
    Box(modifier.chromeFade().testTag(CallTopBarTag).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // The window's own controls own the top-start corner (macOS traffic lights), so desktop chrome sits at the end.
        if (!desktop) ChromeIconButton(Icons.Default.KeyboardArrowDown, stringResource(MR.string.call_minimize), actions.minimize, Modifier.align(Alignment.TopStart))
        val narrow = LocalDesktopTier.current == DesktopTier.Compact || minimal
        val code = ui.verificationCode
        val trailing = (if (code != null) 1 else 0) + (if (mini != null) 1 else 0) + (if (desktop) 1 else 0)
        val reserve = (trailing * 48).dp
        if (center != null) {
            Box(
                if (narrow) Modifier.align(Alignment.TopStart).padding(end = reserve)
                else Modifier.align(Alignment.TopCenter).padding(horizontal = if (desktop) reserve else 60.dp),
            ) { center() }
        }
        if (code != null || desktop || mini != null) {
            Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (code != null) {
                        ChromeIconButton(
                            Icons.Default.VerifiedUser,
                            stringResource(MR.string.call_verification_show),
                            { codeOpen = !codeOpen },
                            tint = MaterialTheme.colorScheme.primary,
                            selected = codeOpen,
                        )
                    }
                    if (mini != null) {
                        val label = stringResource(MR.string.call_mini_mode)
                        ChromeIconButton(Icons.Default.PictureInPicture, label, mini, tooltip = shortcutTip(label, callShortcutKeys("M", shift = true)))
                    }
                    if (desktop) ChromeIconButton(Icons.AutoMirrored.Filled.Chat, stringResource(MR.string.call_back_to_chat), actions.minimize)
                }
                if (code != null) AnimatedVisibility(codeOpen, enter = fadeIn() + scaleIn(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)), exit = fadeOut() + scaleOut()) {
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
    tooltip: String = description,
) {
    Box(modifier) {
        DesktopTooltip(tooltip, TooltipAnchorPosition.Below) {
            FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.size(40.dp).buttonChrome(CircleShape),
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else chromeColor(),
                    contentColor = tint,
                ),
            ) {
                Icon(icon, contentDescription = description, Modifier.size(20.dp))
            }
        }
    }
}

/** Desktop only: a pointer hovers there, and a touch long-press would hijack a phone's press-and-hold. */
@Composable
internal fun DesktopTooltip(text: String, anchor: TooltipAnchorPosition = TooltipAnchorPosition.Above, content: @Composable () -> Unit) {
    if (LocalDesktopTier.current == null) content() else BarTooltip(text, anchor, content)
}

/** Compose Desktop draws no focus indicator on M3 buttons, so Tab navigation would be invisible without it. */
@Composable
internal fun Modifier.buttonChrome(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.secondary
    return pointerHoverIcon(PointerIcon.Hand)
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(3.dp, ring, shape) else Modifier)
}

/** Lowest, not highest: the video-off stage is surfaceContainerHighest and chrome must stand off it. */
@Composable
internal fun chromeColor() = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.9f)

private const val MiddleDot = "\u00B7"

private val PreviewCorner = 20.dp

private data class PillStatus(val icon: ImageVector, val text: StringResource, val warn: Boolean)

@Composable
private fun NamePill(ui: CallUiState, peerName: String, elapsed: String) {
    val minimal = LocalDesktopTier.current == DesktopTier.Minimal
    val status = when {
        minimal -> null
        ui.quality == CallQuality.Poor -> PillStatus(Icons.Default.Warning, MR.string.call_poor_connection, warn = true)
        else -> null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = chromeColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                Modifier.heightIn(min = if (minimal) 40.dp else 48.dp).padding(horizontal = if (minimal) 14.dp else 20.dp, vertical = if (minimal) 4.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    peerName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val reconnecting = ui.phase == CallPhase.Reconnecting && LocalDesktopTier.current != null
                Row(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (reconnecting) LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.tertiary)
                    Text(
                        when {
                            ui.phase == CallPhase.Connecting -> stringResource(MR.string.call_status_connecting)
                            reconnecting -> stringResource(MR.string.call_status_reconnecting)
                            else -> elapsed
                        },
                        style = MaterialTheme.typography.labelLarge.tabular(),
                        color = if (reconnecting) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
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
        if (!minimal) MutedChip(ui.muted)
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
private fun BoxScope.LocalPreview(handle: VideoHandle, cameraOn: Boolean, large: Boolean, remote: VideoHandle?, onSwap: () -> Unit, fillStage: Boolean, chromeVisible: Boolean) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val rtl = direction == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val springSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Offset>()
    val tier = LocalDesktopTier.current
    val fill = remember { Animatable(if (fillStage) 1f else 0f) }
    LaunchedEffect(fillStage) { fill.animateTo(if (fillStage) 1f else 0f, tween(PreviewShrinkMs, easing = EmphasizedEasing)) }
    val filling by remember { derivedStateOf { fill.value > 0f } }
    BoxWithConstraints(Modifier.matchParentSize().zIndex(if (filling) ChromeLayer / 2 else ChromeLayer * 2)) {
        if (tier == DesktopTier.Minimal && !filling) return@BoxWithConstraints
        val compact = LocalCompactHeight.current
        val (pipWidth, pipHeight) = when {
            tier == DesktopTier.Minimal -> 96.dp to 64.dp
            tier == DesktopTier.Compact -> 144.dp to 96.dp
            tier == DesktopTier.Medium -> 176.dp to 120.dp
            large -> 192.dp to 132.dp
            compact -> 156.dp to 108.dp
            else -> 108.dp to 156.dp
        }
        val insets = if (large) WindowInsets(0) else WindowInsets.safeDrawing
        val pad = insets.asPaddingValues(density)
        val insetStart = with(density) { pad.calculateStartPadding(direction).toPx() }
        val insetTop = with(density) { pad.calculateTopPadding().toPx() }
        val insetEnd = with(density) { pad.calculateEndPadding(direction).toPx() }
        val insetBottom = with(density) { pad.calculateBottomPadding().toPx() }
        val stageW = with(density) { maxWidth.toPx() }
        val stageH = with(density) { maxHeight.toPx() }
        val width = stageW - insetStart - insetEnd
        val height = stageH - insetTop - insetBottom
        val pipW = with(density) { pipWidth.toPx() }
        val pipH = with(density) { pipHeight.toPx() }
        val margin = with(density) { PreviewMargin.toPx() }
        val top = with(density) { (if (!chromeVisible) PreviewMargin else if (tier == DesktopTier.Minimal) 64.dp else if (large) 80.dp else if (compact) CompactPipTop else PipTop).toPx() }
        val bottom = with(density) { (if (!chromeVisible) PreviewMargin else if (tier == DesktopTier.Minimal) 72.dp else if (large) DesktopDockReserve else if (compact) CompactDockReserve else PhoneDockReserve).toPx() }
        val previewDescription = stringResource(MR.string.call_local_preview)
        val swapLabel = stringResource(MR.string.call_swap_views)
        val corners = previewCorners(width, height, pipW, pipH, margin, top, bottom)
        // A narrow window has no top corner clear of the centred name pill.
        val home = if (tier == DesktopTier.Compact || tier == DesktopTier.Minimal) 3 else 1
        var corner by remember(compact, home) { mutableIntStateOf(home) }
        val anchor = remember(width, height, compact) { Animatable(corners[corner], Offset.VectorConverter) }
        var drag by remember(width, height, compact) { mutableStateOf(Offset.Zero) }
        var hidden by rememberSaveable { mutableStateOf(false) }
        val hover = remember { MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        LaunchedEffect(corners[corner]) { anchor.animateTo(corners[corner], springSpec) }
        val shape = RoundedCornerShape(PreviewCorner)
        if (hidden) {
            val restore = stringResource(MR.string.call_show_self_view)
            Box(Modifier.offset { (anchor.value + Offset(pipW, pipH) - with(density) { Offset(48.dp.toPx(), 48.dp.toPx()) }).round() }) {
                ChromeIconButton(Icons.Default.Visibility, restore, { hidden = false })
            }
            return@BoxWithConstraints
        }
        Surface(
            modifier = Modifier
                .offset { lerp(anchor.value + drag + Offset(insetStart, insetTop), Offset.Zero, fill.value).round() }
                .layout { measurable, _ ->
                    val w = (pipW + (stageW - pipW) * fill.value).roundToInt()
                    val h = (pipH + (stageH - pipH) * fill.value).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                }
                .semantics {
                    contentDescription = previewDescription
                    if (!fillStage) customActions = listOf(CustomAccessibilityAction(swapLabel) { onSwap(); true })
                }
                .then(
                    if (fillStage) Modifier else Modifier
                        .hoverable(hover)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(onSwap) { detectTapGestures { onSwap() } }
                        .pointerInput(width, height, rtl, compact) {
                            detectDragGestures(
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag += if (rtl) Offset(-amount.x, amount.y) else amount
                                },
                                onDragEnd = {
                                    val landed = anchor.value + drag
                                    val target = nearestPreviewCorner(corners, landed)
                                    corner = corners.indexOf(target)
                                    drag = Offset.Zero
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
            Box {
                if (remote != null) {
                    CallVideoSurface(remote, Modifier.fillMaxSize().clip(shape), cornerRadius = PreviewCorner)
                } else if (cameraOn) {
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
                if (tier != null) {
                    val hide = stringResource(MR.string.call_hide_self_view)
                    AnimatedVisibility(hovered, Modifier.align(Alignment.TopEnd).padding(6.dp), enter = fadeIn(), exit = fadeOut()) {
                        DesktopTooltip(hide, TooltipAnchorPosition.Below) {
                            FilledTonalIconButton(
                                onClick = { hidden = true },
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = chromeColor(), contentColor = MaterialTheme.colorScheme.onSurface),
                            ) { Icon(Icons.Default.VisibilityOff, hide, Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        }
    }
}

private const val PreviewShrinkMs = 400
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** [placing] is the outgoing ring: only the controls that matter before the peer answers. */
@Composable
private fun ControlDock(ui: CallUiState, large: Boolean, actions: CallScreenActions, modifier: Modifier = Modifier, placing: Boolean) {
    val modifier = modifier.chromeFade().testTag(CallDockTag)
    if (LocalDesktopTier.current == DesktopTier.Minimal) {
        MinimalDock(ui, actions, modifier)
        return
    }
    val compact = LocalCompactHeight.current
    val showLabels = !large && !compact
    // Desktop pickers choose the real output and camera, so the phone route and flip toggles would only duplicate them.
    val pickers = ui.devices.isNotEmpty()
    val showRoute = ui.availableRoutes.size > 1 && !pickers
    // A desktop voice call can turn the camera on mid-call; the engine renegotiates when there is no video track yet.
    val showCamera = ui.videos.local != null || (LocalDesktopTier.current != null && ui.inProgress && !placing)
    // Desktop webcams are single-facing, so flip only earns a slot on phone widths.
    val showFlip = ui.localVideo && !large && !placing && !pickers
    // With pickers each of mic and camera is a split button: a toggle segment and its own menu segment in the same group.
    val split = if (pickers) 1 else 0
    val count = 1 + split + listOf(showRoute, showCamera, showFlip).count { it } + (if (showCamera) split else 0)
    val routeSlot = 1 + split
    val cameraSlot = routeSlot + if (showRoute) 1 else 0
    val flipSlot = cameraSlot + if (showCamera) 1 + split else 0
    val micDevice = currentDeviceName(ui, MediaDeviceKind.Mic)
    val muteTip = shortcutTip(stringResource(if (ui.muted) MR.string.call_unmute else MR.string.call_mute), callShortcutKeys("D"))
    val cameraTip = shortcutTip(stringResource(if (ui.localVideo) MR.string.call_camera_off else MR.string.call_camera_on), callShortcutKeys("E"))
    val toggles: @Composable ButtonGroupScope.() -> Unit = {
        val muteInteraction = remember { MutableInteractionSource() }
        CallToggle(
            tooltip = muteTip,
            checked = ui.muted,
            onCheckedChange = { actions.toggleMute() },
            icon = if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic,
            label = stringResource(MR.string.call_label_mute).takeIf { showLabels },
            description = stringResource(if (ui.muted) MR.string.call_unmute else MR.string.call_mute),
            state = when {
                ui.muted -> stringResource(MR.string.call_state_muted)
                micDevice != null -> stringResource(MR.string.call_state_mic_on_device, micDevice)
                else -> stringResource(MR.string.call_state_mic_on)
            },
            shapes = segmentShapes(0, count),
            interaction = muteInteraction,
            modifier = Modifier.animateWidth(muteInteraction),
            level = LocalMicLevel.current.takeIf { pickers && !ui.muted && ui.micLevelSeen },
        )
        if (pickers) DeviceMenuSegment(ui, MediaDeviceKind.Mic, actions, segmentShapes(1, count))
        if (showRoute) {
            val routeInteraction = remember { MutableInteractionSource() }
            RouteToggle(ui, actions, showLabels, segmentShapes(routeSlot, count), routeInteraction, Modifier.animateWidth(routeInteraction))
        }
        if (showCamera) {
            val cameraInteraction = remember { MutableInteractionSource() }
            val off = !ui.localVideo
            CallToggle(
                tooltip = cameraTip,
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
            if (pickers) DeviceMenuSegment(ui, MediaDeviceKind.Camera, actions, segmentShapes(cameraSlot + 1, count))
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
    if (large || compact) {
        DockSurface(modifier.padding(bottom = if (compact) 12.dp else 28.dp), 40.dp) {
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ButtonGroup(horizontalArrangement = Arrangement.spacedBy(SegmentGap)) { toggles() }
                VerticalDivider(Modifier.height(40.dp).padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val tight = LocalDesktopTier.current == DesktopTier.Compact
                EndCallButton(actions.hangup, Modifier.size(width = if (tight) 76.dp else 96.dp, height = if (tight) 52.dp else 56.dp))
            }
        }
        return
    }
    DockSurface(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), 40.dp) {
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

@Composable
private fun DockSurface(modifier: Modifier, radius: Dp, content: @Composable () -> Unit) = Surface(
    modifier = modifier,
    shape = RoundedCornerShape(radius),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    shadowElevation = 6.dp,
    content = content,
)

private val ToggleSize = 64.dp

@Composable
private fun toggleSize(): Dp = when (LocalDesktopTier.current) {
    DesktopTier.Minimal -> 44.dp
    DesktopTier.Compact -> 52.dp
    else -> ToggleSize
}

internal const val CallDockTag = "call-dock"
internal const val CallTopBarTag = "call-top-bar"

@Composable
internal fun Modifier.chromeFade(): Modifier {
    val visible = LocalChromeVisible.current
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(250), label = "chromeFade")
    return graphicsLayer { this.alpha = alpha }
        .pointerInput(visible) {
            if (visible) return@pointerInput
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
        .semantics { if (!visible) invisibleToUser() }
}

@Composable
private fun MinimalDock(ui: CallUiState, actions: CallScreenActions, modifier: Modifier) {
    val showCamera = ui.videos.local != null
    val muteDescription = stringResource(if (ui.muted) MR.string.call_unmute else MR.string.call_mute)
    val cameraDescription = stringResource(if (ui.localVideo) MR.string.call_camera_off else MR.string.call_camera_on)
    val single = IconToggleButtonShapes(CircleShape, RoundedCornerShape(16.dp), CircleShape)
    var devicesOpen by remember { mutableStateOf(false) }
    HoldChromeWhile(devicesOpen)
    DockSurface(modifier.padding(bottom = 8.dp), 32.dp) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CallToggle(
                tooltip = shortcutTip(muteDescription, callShortcutKeys("D")),
                checked = ui.muted,
                onCheckedChange = { actions.toggleMute() },
                icon = if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic,
                label = null,
                description = muteDescription,
                state = stringResource(if (ui.muted) MR.string.call_state_muted else MR.string.call_state_mic_on),
                shapes = single,
                interaction = remember { MutableInteractionSource() },
                modifier = Modifier,
            )
            if (showCamera) {
                CallToggle(
                    tooltip = shortcutTip(cameraDescription, callShortcutKeys("E")),
                    checked = !ui.localVideo,
                    onCheckedChange = { actions.toggleVideo() },
                    icon = if (ui.localVideo) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    label = null,
                    description = cameraDescription,
                    state = stringResource(if (ui.localVideo) MR.string.call_state_camera_on else MR.string.call_no_video),
                    shapes = single,
                    interaction = remember { MutableInteractionSource() },
                    modifier = Modifier,
                )
            }
            if (ui.devices.isNotEmpty()) {
                val devices = stringResource(MR.string.call_devices)
                Box {
                    DesktopTooltip(devices) {
                        FilledTonalIconButton(
                            onClick = { devicesOpen = true },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.size(toggleSize()).buttonChrome(CircleShape),
                        ) { Icon(Icons.Default.Tune, devices, Modifier.size(24.dp)) }
                    }
                    CallDeviceMenu(devicesOpen, { devicesOpen = false }, ui, MediaDeviceKind.entries, actions.selectDevice)
                }
            }
            EndCallButton(actions.hangup, Modifier.size(width = 64.dp, height = toggleSize()))
        }
    }
}

@Composable
internal fun shortcutTip(action: String, key: String): String = stringResource(MR.string.call_tooltip_shortcut, action, key)

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
    tooltip: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    label: String?,
    description: String,
    state: String,
    shapes: IconToggleButtonShapes,
    interaction: MutableInteractionSource,
    modifier: Modifier,
    level: StateFlow<Float>? = null,
) {
    ToggleColumn(label, modifier) {
        DesktopTooltip(tooltip ?: description) {
            FilledTonalIconToggleButton(
                checked = checked,
                onCheckedChange = onCheckedChange,
                shapes = shapes,
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    checkedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                    checkedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().height(toggleSize()).buttonChrome(shapes.shape).semantics {
                    contentDescription = description
                    stateDescription = state
                },
            ) {
                val halo = MaterialTheme.colorScheme.primary
                val shown = remember { Animatable(0f) }
                if (level != null) {
                    LaunchedEffect(level) { level.collect { shown.animateTo(it, tween(90)) } }
                }
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp).then(
                        if (level == null) Modifier else Modifier.drawBehind {
                            // A halo that swells with the voice: an arc read as a loading spinner, and "connecting" is a real call state.
                            val amount = shown.value
                            if (amount > 0.02f) {
                                val radius = (15.dp + 9.dp * amount).toPx()
                                drawCircle(halo.copy(alpha = 0.22f + 0.33f * amount), radius = radius)
                                drawCircle(halo.copy(alpha = 0.5f + 0.5f * amount), radius = radius, style = Stroke(1.5.dp.toPx()))
                            }
                        },
                    ),
                )
            }
        }
    }
}

/** The menu half of a split button: its own hit target, hover, press and focus, so it can never be mistaken for mute. */
@Composable
private fun DeviceMenuSegment(ui: CallUiState, kind: MediaDeviceKind, actions: CallScreenActions, shapes: IconToggleButtonShapes) {
    val request = LocalDeviceMenuRequest.current
    val open = request.value == kind
    // Mic and speaker share one audio menu, as Meet and Zoom do, so the output is one click from the dock too.
    val kinds = if (kind == MediaDeviceKind.Mic) listOf(MediaDeviceKind.Mic, MediaDeviceKind.Speaker) else listOf(kind)
    val description = stringResource(kind.label())
    val current = currentDeviceName(ui, kind)
    val tip = stringResource(
        MR.string.call_device_current,
        description,
        current ?: stringResource(MR.string.call_device_system_default),
    )
    val interaction = remember { MutableInteractionSource() }
    Box {
        DesktopTooltip(tip) {
            FilledTonalIconButton(
                onClick = { request.value = kind },
                shapes = IconButtonDefaults.shapes(shape = shapes.shape, pressedShape = shapes.pressedShape),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (open) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (open) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                interactionSource = interaction,
                modifier = Modifier.size(width = MenuSegmentWidth, height = toggleSize()).buttonChrome(shapes.shape).semantics {
                    contentDescription = description
                    role = Role.DropdownList
                    if (current != null) stateDescription = current
                },
            ) {
                val turn by animateFloatAsState(if (open) 180f else 0f, label = "menuChevron")
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
            }
        }
        CallDeviceMenu(open, { if (request.value == kind) request.value = null }, ui, kinds, actions.selectDevice)
    }
}

private val MenuSegmentWidth = 32.dp

/** Wider than the M3 connected gap: on a dark dock 2dp let mic and its chevron read as one target. */
private val SegmentGap = 4.dp

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
            modifier = Modifier.fillMaxWidth().height(toggleSize()).buttonChrome(shapes.shape).semantics {
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
        modifier.width(if (label != null) 72.dp else toggleSize()),
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
    EndScaffold(
        large = large,
        headerBottom = if (large) 120.dp else if (LocalCompactHeight.current) 80.dp else 170.dp,
        spacing = 12.dp,
        header = { modifier -> PeerHeader(name = peerName, status = status, avatar = avatar, halo = HaloMode.None, dimmed = true, large = large, modifier = modifier) },
    ) { slot ->
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

/** A desktop window can be any height, so there the header and its actions flow as one scrollable column instead of pinning to opposite edges. */
@Composable
private fun BoxScope.EndScaffold(
    large: Boolean,
    headerBottom: Dp,
    spacing: Dp,
    header: @Composable (Modifier) -> Unit,
    buttons: @Composable (slot: Modifier) -> Unit,
) {
    if (LocalDesktopTier.current == null) {
        header(Modifier.align(Alignment.Center).padding(bottom = headerBottom))
        EndActions(large, spacing, buttons)
        return
    }
    Column(
        Modifier.align(Alignment.Center).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        header(Modifier)
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) { buttons(Modifier.weight(1f)) }
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
    EndScaffold(
        large = large,
        headerBottom = if (large) 72.dp else if (compact) 80.dp else 120.dp,
        spacing = 8.dp,
        header = { modifier -> FailedHeader(badge, title, body, peerName, avatar, modifier) },
    ) { slot ->
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
private fun FailedHeader(badge: ImageVector, title: String, body: String, peerName: String, avatar: @Composable (Dp) -> Unit, modifier: Modifier) {
    val compact = LocalCompactHeight.current
    Column(
        modifier.widthIn(max = 420.dp).padding(horizontal = 32.dp),
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
fun OngoingCallBar(
    ui: CallUiState,
    peerName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleMute: (() -> Unit)? = null,
    onHangup: (() -> Unit)? = null,
) {
    val elapsed = rememberElapsedText(ui.connectedAtMs)
    val description = stringResource(MR.string.call_pill_description, peerName)
    val status = callStatusLabel(ui, elapsed)
    val warn = ui.phase == CallPhase.Reconnecting
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(OngoingCallBarTag).semantics { contentDescription = description; role = Role.Button },
        color = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LiveCallDot(warn, ui.isVideoCall)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    peerName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(MiddleDot, style = MaterialTheme.typography.labelLarge)
                Text(
                    status,
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    maxLines = 1,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (ui.muted) Icon(Icons.Default.MicOff, stringResource(MR.string.call_you_are_muted), Modifier.size(16.dp))
            }
            Text(
                stringResource(MR.string.call_bar_return),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { },
            )
            if (onToggleMute != null) {
                val label = stringResource(if (ui.muted) MR.string.call_unmute else MR.string.call_mute)
                val stateText = stringResource(if (ui.muted) MR.string.call_state_muted else MR.string.call_state_mic_on)
                BarTooltip(label) {
                    FilledTonalIconToggleButton(
                        checked = ui.muted,
                        onCheckedChange = { onToggleMute() },
                        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            checkedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                            checkedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        ),
                        modifier = Modifier.size(36.dp).semantics { contentDescription = label; stateDescription = stateText },
                    ) {
                        Icon(if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic, null, Modifier.size(20.dp))
                    }
                }
            }
            if (onHangup != null) {
                val label = stringResource(MR.string.call_bar_end, peerName)
                BarTooltip(stringResource(MR.string.call_hang_up)) {
                    Surface(
                        onClick = onHangup,
                        shape = CircleShape,
                        color = endCallContainer(),
                        contentColor = endCallContent(),
                        modifier = Modifier.size(width = 52.dp, height = 36.dp).semantics { contentDescription = label; role = Role.Button },
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CallEnd, null, Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCallDot(warn: Boolean, video: Boolean) {
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse))
    val color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(Modifier.size(28.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(if (video) Icons.Default.Videocam else Icons.Default.PhoneInTalk, null, Modifier.size(16.dp).alpha(pulse), tint = color)
    }
}

@Composable
internal fun BarTooltip(text: String, anchor: TooltipAnchorPosition = TooltipAnchorPosition.Below, content: @Composable () -> Unit) {
    val state = rememberTooltipState()
    HoldChromeWhile(state.isVisible)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(anchor),
        tooltip = { PlainTooltip { Text(text) } },
        state = state,
        content = content,
    )
}

const val OngoingCallBarTag = "ongoing-call-bar"

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

internal fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

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
    val tier = LocalDesktopTier.current
    val avatarSize = when {
        tier == DesktopTier.Minimal -> 72.dp
        compact -> 72.dp
        tier == DesktopTier.Compact -> 112.dp
        tier == DesktopTier.Medium -> 136.dp
        tier == DesktopTier.Expanded -> 184.dp
        large -> 152.dp
        else -> 136.dp
    }
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
                tier == DesktopTier.Minimal -> MaterialTheme.typography.titleLargeEmphasized
                compact || tier == DesktopTier.Compact -> MaterialTheme.typography.headlineMediumEmphasized
                tier == DesktopTier.Medium -> MaterialTheme.typography.headlineLargeEmphasized
                tier == DesktopTier.Expanded -> MaterialTheme.typography.displaySmallEmphasized
                large -> MaterialTheme.typography.displayMediumEmphasized
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
                color = if (tier != null && !dimmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
internal fun AvatarHalo(avatarSize: Dp, mode: HaloMode) {
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
                modifier = Modifier.size(size).buttonChrome(RoundedCornerShape(corner)).semantics { contentDescription = description; role = Role.Button },
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

/** Dark M3 `error` is a pale salmon that reads as disabled; its errorContainer is the saturated red, so ending looks as final in both themes. */
@Composable
internal fun endCallContainer(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.error

@Composable
internal fun endCallContent(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onError

@Composable
internal fun EndCallButton(onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner = pressMorphCorner(64.dp, pressed)
    val description = stringResource(MR.string.call_hang_up)
    val shape = RoundedCornerShape(corner)
    DesktopTooltip(shortcutTip(description, callShortcutKeys("H", shift = true))) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = endCallContainer(),
            contentColor = endCallContent(),
            interactionSource = interaction,
            modifier = modifier.buttonChrome(shape).semantics { contentDescription = description; role = Role.Button },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CallEnd, null, Modifier.size(30.dp)) }
        }
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
                val disc = (minOf(maxWidth, maxHeight) * 0.42f).coerceAtMost(176.dp)
                Box(Modifier.size(disc).background(ink.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                    Text(
                        handle.initials,
                        style = MaterialTheme.typography.headlineLargeEmphasized.copy(fontSize = (disc.value * 0.34f).sp),
                        color = ink.copy(alpha = 0.8f),
                    )
                }
            }
        }
        else -> CallVideoView(handle, modifier, cornerRadius)
    }
}

@Composable
internal fun rememberElapsedText(connectedAtMs: Long?): String = rememberElapsed(connectedAtMs)()

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
