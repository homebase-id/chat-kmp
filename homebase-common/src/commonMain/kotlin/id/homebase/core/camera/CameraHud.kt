package id.homebase.core.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.Haptics
import id.homebase.core.util.formatHms
import id.homebase.resources.MR
import id.homebase.resources.camera_error_bind
import id.homebase.resources.camera_error_in_use
import id.homebase.resources.camera_error_interrupted
import id.homebase.resources.camera_error_photo
import id.homebase.resources.camera_error_recording
import id.homebase.resources.camera_error_storage
import id.homebase.resources.camera_recording_started
import id.homebase.resources.camera_recording_stopped
import id.homebase.resources.camera_saving
import id.homebase.resources.camera_shutter_hold_hint
import id.homebase.resources.camera_shutter_photo
import id.homebase.resources.camera_shutter_stop
import id.homebase.resources.camera_shutter_video
import id.homebase.resources.camera_zoom_level
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.time.Clock
import kotlin.time.TimeSource

internal const val PREVIEW_TAG = "camera_preview"

private const val HOLD_ZOOM_CEILING = 10f
private const val FLING_DP_PER_SECOND = 800f
// The shutter press has to be seen before the window closes on a fast capture.
private const val CAPTURE_FEEDBACK_MS = 180L
private const val ZOOM_READOUT_LINGER_MS = 600L
private const val PRESET_ARRIVAL_TOLERANCE = 0.005f
private val ZoomBarFullRange = 240.dp
private val ExposureTravel = 160.dp
internal val ShutterRowPadding = 32.dp
private val ShutterRowMaxWidth = 480.dp
private val RailGap = 24.dp
private val SidewaysSnackbarMaxWidth = 480.dp
private val SidewaysSnackbarClearance = 192.dp
private val CarouselHideDrop = 8.dp
private val TopBarHeight = 64.dp
private const val FROZEN_FRAME_DIM = 0.6f

/**
 * Once the preview has streamed, a rebind (flip, mode switch) dims the frame the surface still holds instead of
 * blacking it out; a first start has no frame to keep. A flip stays dimmed until the new lens's first frame.
 */
/** Only a delivered frame counts: the camera reports bound (open, running) before the preview shows anything. */
internal fun previewHasShown(shownBefore: Boolean, ui: CameraUiState): Boolean =
    shownBefore || (ui.isBound && !ui.awaitingFirstFrame)

internal fun previewScrimAlpha(isBound: Boolean, awaitingFirstFrame: Boolean, previewShown: Boolean): Float = when {
    isBound && !awaitingFirstFrame -> 0f
    previewShown -> FROZEN_FRAME_DIM
    else -> 1f
}

/** Pins a letterboxed preview under the top bar, as the iOS Camera app does, rising only as far as a short screen needs. */
internal fun letterboxTop(screenHeight: Dp, frameHeight: Dp, topClearance: Dp): Dp =
    minOf(topClearance, screenHeight - frameHeight).coerceAtLeast(0.dp)

// A normal lens flip rebinds in about half a second; only a slower start earns a spinner.
private const val STARTING_SPINNER_DELAY_MS = 700L

@Composable
internal fun CameraCaptureContent(
    engine: CameraEngine,
    allowedModes: CameraModes,
    initialMode: CaptureMode,
    mirrorFront: Boolean,
    mic: MicPermission,
    onRequestMic: () -> Unit,
    haptics: Haptics,
    deviceRotation: QuarterTurn,
    onResult: (PlatformFile) -> Unit,
    onDismiss: () -> Unit,
    displayRotation: QuarterTurn = QuarterTurn.R0,
    acceptsInput: Boolean = true,
    onOpenGallery: (() -> Unit)? = null,
    galleryThumbnail: ImageBitmap? = null,
    preview: @Composable (Modifier) -> Unit = {
        CameraPreview(engine, it, onLongPressFocus = { haptics.perform(HapticEvent.Confirm) })
    },
) {
    val liveUi = engine.uiState.collectAsStateWithLifecycle()
    // Zoom and exposure change on every frame of a drag; only the controls that show them read them, via liveUi.
    val ui by remember(liveUi) { derivedStateOf { liveUi.value.copy(zoomRatio = 1f, exposureBias = 0f) } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val reduceMotion = LocalReduceMotion.current
    val motionScheme = MaterialTheme.motionScheme
    val captureBlink = remember { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var heldRecording by remember { mutableStateOf(false) }
    // Leads the engine: set on the frame a recording is asked for and cleared on the frame it's stopped, so every
    // control switches look together instead of waiting for the engine's start/stop callbacks.
    var recordingIntent by remember { mutableStateOf(false) }
    var previewShown by remember { mutableStateOf(false) }
    var holdZoomBase by remember { mutableFloatStateOf(1f) }
    var lockProgress by remember { mutableFloatStateOf(0f) }
    var returnToPhotoAfterHold by remember { mutableStateOf(false) }
    var lensTurns by remember { mutableFloatStateOf(0f) }
    var announcement by remember { mutableStateOf("") }
    var zoomGesture by remember { mutableStateOf(false) }
    var barZoomBase by remember { mutableFloatStateOf(1f) }
    var barTravel by remember { mutableFloatStateOf(0f) }
    // A tapped preset shows as selected at once while the zoom ramps up to it underneath.
    var presetTarget by remember { mutableStateOf<Float?>(null) }
    var focusGate by remember { mutableStateOf(false) }
    var ignoredFocus by remember { mutableStateOf<Offset?>(null) }
    var keyDown by remember { mutableStateOf(false) }
    var keyHoldStarted by remember { mutableStateOf(false) }
    var keyHoldJob by remember { mutableStateOf<Job?>(null) }
    val currentUi by liveUi
    val currentMic by rememberUpdatedState(mic)
    val currentOnResult by rememberUpdatedState(onResult)
    val currentAcceptsInput by rememberUpdatedState(acceptsInput)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentIsRtl by rememberUpdatedState(isRtl)
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val modes = remember(allowedModes) { CaptureMode.entries.filter { allowedModes.allows(it) } }
    val carousel = remember(modes) {
        ModeCarouselState(modes.indexOf(engine.uiState.value.mode).coerceAtLeast(0), modes.size, scope)
    }
    val presets = remember(ui.minZoom, ui.maxZoom, ui.lensSwitchRatios) {
        ZoomPresets.available(ui.minZoom, ui.maxZoom, ui.lensSwitchRatios)
    }
    val currentPresets by rememberUpdatedState(presets)
    val recordingStartedText = stringResource(MR.string.camera_recording_started)
    val holdEnabled = CaptureButtonState.holdToRecordAllowed(allowedModes)

    LaunchedEffect(engine) {
        val mode = if (allowedModes.allows(initialMode)) initialMode else CaptureMode.Photo
        engine.setMode(mode)
    }
    LaunchedEffect(ui.mode, mic.needsAsking) {
        if (ui.mode == CaptureMode.Video && mic.needsAsking) onRequestMic()
    }
    LaunchedEffect(engine, mirrorFront) { engine.setMirrorFront(mirrorFront) }
    LaunchedEffect(engine, deviceRotation) { engine.setCaptureRotation(deviceRotation) }
    LaunchedEffect(engine) {
        engine.errors.collect { error ->
            if (error is CameraError.RecordingFailed && recordingIntent && !currentUi.isRecording) {
                recordingIntent = false
                heldRecording = false
                if (returnToPhotoAfterHold) {
                    returnToPhotoAfterHold = false
                    engine.setMode(CaptureMode.Photo)
                }
            }
            val message = error.messageRes ?: return@collect
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(getString(message))
        }
    }
    // A hold from Photo that rebinds to Video still reads as Photo, so nothing flickers to Video and back.
    val displayMode = if (returnToPhotoAfterHold) CaptureMode.Photo else ui.mode
    val selectedIndex = modes.indexOf(displayMode).coerceAtLeast(0)
    LaunchedEffect(selectedIndex) { carousel.settleTo(selectedIndex, currentReduceMotion) }
    LaunchedEffect(presetTarget) {
        val target = presetTarget ?: return@LaunchedEffect
        snapshotFlow { liveUi.value.zoomRatio }.first { abs(it - target) <= target * PRESET_ARRIVAL_TOLERANCE }
        presetTarget = null
    }
    LaunchedEffect(ui.lens) { presetTarget = null }
    LaunchedEffect(ui.focusPoint) {
        if (focusGate && ui.focusPoint != null) ignoredFocus = ui.focusPoint
    }

    fun deliver(file: PlatformFile?) {
        if (file != null) currentOnResult(file)
    }

    fun stopRecording(requested: Boolean = true) {
        if (stopping) return
        stopping = true
        // The watcher below sees isRecording drop after this stop returns; it must not take it for a second end.
        stopRequested = requested
        recordingIntent = false
        heldRecording = false
        busy = true
        haptics.perform(HapticEvent.Confirm)
        val startedAt = currentUi.recordingStartedAtMs
        scope.launch {
            val elapsed = startedAt?.let { Clock.System.now().toEpochMilliseconds() - it } ?: 0L
            announcement = getString(MR.string.camera_recording_stopped, formatHms(elapsed.coerceAtLeast(0L)))
            val file = engine.stopRecording()
            busy = false
            stopping = false
            deliver(file)
            // A delivered clip closes the camera; rebinding to Photo under the closing window only paints black frames.
            if (returnToPhotoAfterHold) {
                returnToPhotoAfterHold = false
                if (file == null) engine.setMode(CaptureMode.Photo)
            }
        }
    }

    // A recording the platform ended on its own (backgrounding, interruption, storage) still gets delivered.
    LaunchedEffect(engine) {
        var wasRecording = false
        engine.uiState.map { it.isRecording }.distinctUntilChanged().collect { recording ->
            if (wasRecording && !recording) {
                if (stopRequested) stopRequested = false else stopRecording(requested = false)
            }
            wasRecording = recording
        }
    }

    fun startRecording(held: Boolean): Boolean {
        if (busy || recordingIntent || currentUi.isRecording) return false
        if (currentMic.needsAsking) {
            onRequestMic()
            return false
        }
        // The rebind is synchronous on Android; the stop below switches back to Photo once the clip is saved.
        if (held && CaptureButtonState.holdSwitchesToVideo(currentUi.mode, currentUi.supportsSimultaneousVideo)) {
            returnToPhotoAfterHold = true
            engine.setMode(CaptureMode.Video)
        }
        heldRecording = held
        recordingIntent = true
        stopRequested = false
        lockProgress = 0f
        haptics.perform(HapticEvent.LongPress)
        announcement = recordingStartedText
        engine.startRecording(withAudio = currentMic.granted == true)
        return true
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        haptics.perform(HapticEvent.Confirm)
        val pressedAt = TimeSource.Monotonic.markNow()
        scope.launch {
            captureBlink.snapTo(0f)
            captureBlink.animateTo(0.9f, motionScheme.fastEffectsSpec())
            captureBlink.animateTo(0f, motionScheme.defaultEffectsSpec())
        }
        scope.launch {
            val file = engine.takePhoto()
            val remaining = CAPTURE_FEEDBACK_MS - pressedAt.elapsedNow().inWholeMilliseconds
            if (file != null && remaining > 0) delay(remaining)
            busy = false
            deliver(file)
        }
    }

    fun isRecordingNow() = recordingIntent || (currentUi.isRecording && !stopping)

    fun currentButtonState() = CaptureButtonState.of(currentUi.mode, isRecordingNow(), isRecordingLocked = !heldRecording)

    fun shutterTap() {
        when (currentButtonState().tapAction) {
            CaptureAction.TakePhoto -> takePhoto()
            CaptureAction.StartLockedRecording -> startRecording(held = false)
            CaptureAction.StopRecording -> stopRecording()
            else -> Unit
        }
    }

    fun lockRecording() {
        if (!heldRecording) return
        heldRecording = false
        haptics.perform(HapticEvent.Confirm)
    }

    fun flipLens() {
        val state = currentUi
        if (state.isRecording || recordingIntent || busy || !state.hasFrontLens || !state.hasBackLens) return
        lensTurns += 180f
        haptics.perform(HapticEvent.Tick)
        // The taps of a double-tap flip also reach the preview's tap-to-focus; neither should leave a ring.
        focusGate = true
        ignoredFocus = state.focusPoint
        scope.launch {
            delay(viewConfiguration.doubleTapTimeoutMillis * 2)
            focusGate = false
        }
        engine.setLens(if (state.lens == CameraLens.Back) CameraLens.Front else CameraLens.Back)
    }

    fun selectMode(mode: CaptureMode, haptic: Boolean = true): Boolean {
        if (currentUi.isRecording || recordingIntent || busy || !allowedModes.allows(mode)) return false
        if (mode == currentUi.mode) return true
        if (haptic) haptics.perform(HapticEvent.Selection)
        engine.setMode(mode)
        return true
    }

    fun zoomTo(ratio: Float) {
        presetTarget = null
        val state = currentUi
        val target = state.clampZoom(ratio)
        val steps = currentPresets
        if (steps.presetStep(state.zoomRatio) != steps.presetStep(target)) haptics.perform(HapticEvent.Selection)
        engine.setZoomRatio(target)
    }

    val carouselDirection = { if (currentIsRtl) -1f else 1f }
    fun modeDragStart() = carousel.dragStart(modes.indexOf(currentUi.mode).coerceAtLeast(0))
    fun modeDrag(deltaPx: Float, slotPx: Float) =
        carousel.drag(-deltaPx * carouselDirection() / slotPx) { haptics.perform(HapticEvent.Selection) }
    fun modeDragEnd(velocityPx: Float, slotPx: Float) {
        val flung = abs(velocityPx) > with(density) { FLING_DP_PER_SECOND.dp.toPx() }
        Logger.i(tag = SWIPE_LOG_TAG) {
            "drag end velocityPx=$velocityPx slotPx=$slotPx flung=$flung mode=${currentUi.mode} " +
                "recording=${currentUi.isRecording} intent=$recordingIntent busy=$busy"
        }
        carousel.release(
            velocitySlotsPerSecond = -velocityPx * carouselDirection() / slotPx,
            flung = flung,
            reduceMotion = currentReduceMotion,
            commit = { index -> selectMode(modes[index], haptic = false) },
            onSlotChange = { haptics.perform(HapticEvent.Selection) },
        )
    }

    fun shutterKeyDown() {
        if (keyDown || !currentAcceptsInput) return
        keyDown = true
        keyHoldStarted = false
        if (currentButtonState().longPressAction(holdEnabled) == null || !currentUi.isBound) return
        keyHoldJob = scope.launch {
            delay(viewConfiguration.longPressTimeoutMillis)
            holdZoomBase = currentUi.zoomRatio
            keyHoldStarted = startRecording(held = true)
        }
    }

    fun shutterKeyUp() {
        if (!keyDown) return
        keyDown = false
        keyHoldJob?.cancel()
        keyHoldJob = null
        if (!currentAcceptsInput) return
        if (keyHoldStarted) {
            keyHoldStarted = false
            if (heldRecording) stopRecording()
        } else if (currentUi.isBound || currentUi.isRecording) {
            shutterTap()
        }
    }

    val previewGestures = remember(engine) {
        object : PreviewGestureHandler {
            override fun onPinch(zoom: Float) {
                zoomGesture = true
                zoomTo(currentUi.zoomRatio * zoom)
            }
            override fun onPinchEnd() {
                zoomGesture = false
            }
            override fun canDragMode() = modes.size > 1 && !currentUi.isRecording && !recordingIntent && !busy
            override fun onModeDragStart() = modeDragStart()
            override fun onModeDrag(deltaX: Float, slotPx: Float) = modeDrag(deltaX, slotPx)
            override fun onModeDragEnd(velocityX: Float, slotPx: Float) = modeDragEnd(velocityX, slotPx)
            override fun canDragExposure() = currentUi.exposureSupported && currentUi.focusPoint != null
            override fun onExposureDrag(deltaY: Float) {
                val travel = with(density) { ExposureTravel.toPx() }
                val from = currentUi.exposureBias
                val to = (from - deltaY / travel).coerceIn(-1f, 1f)
                if (from != 0f && (to == 0f || sign(to) != sign(from))) haptics.perform(HapticEvent.Tick)
                engine.setExposureBias(to)
            }
        }
    }

    KeepScreenOnEffect(ui.isRecording)
    HardwareShutterEffect(onDown = ::shutterKeyDown, onUp = ::shutterKeyUp)

    if (!ui.isAvailable) {
        CameraUnavailablePane(onDismiss = onDismiss)
        return
    }

    val looksRecording = recordingIntent || (ui.isRecording && !stopping)
    val buttonState = CaptureButtonState.of(displayMode, looksRecording, isRecordingLocked = !heldRecording)
    val videoIndex = modes.indexOf(CaptureMode.Video)
    val videoAmount = { if (videoIndex < 0) 0f else 1f - abs(carousel.position - videoIndex).coerceIn(0f, 1f) }
    val uprightDegrees = deviceRotation.uprightIconDegrees(displayRotation)
    val iconRotationState = animatedUprightRotation(uprightDegrees)
    val iconRotation = { iconRotationState.value }
    val sideways = abs(uprightDegrees) % 180f == 90f
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyFocus.requestFocus() }

    // No background here: CameraCaptureScreen already paints the scrim, and each full-screen fill costs GPU per frame.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.key != Key.VolumeUp && event.key != Key.VolumeDown) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> shutterKeyDown()
                    KeyEventType.KeyUp -> shutterKeyUp()
                }
                true
            }
            .focusRequester(keyFocus)
            .focusTarget(),
    ) {
        val rail = maxWidth > maxHeight
        val frameAspect = ui.previewAspectRatio?.takeUnless { rail }
        val safeTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
        val frame = if (frameAspect == null) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .padding(top = letterboxTop(maxHeight, maxWidth / frameAspect, safeTop + TopBarHeight))
                .fillMaxWidth()
                .aspectRatio(frameAspect)
        }
        // Gestures sit on the preview's parent, not the HUD's, so a quick double tap on the shutter isn't a flip.
        Box(
            Modifier
                .fillMaxSize()
                .testTag(PREVIEW_TAG)
                .pointerInput(previewGestures) { detectPreviewGestures(previewGestures) }
                .pointerInput(engine) { detectDoubleTapObserving { flipLens() } },
        ) {
            preview(frame)
        }

        LaunchedEffect(ui.isBound, ui.awaitingFirstFrame) { previewShown = previewHasShown(previewShown, ui) }
        val blackout by animateFloatAsState(
            targetValue = previewScrimAlpha(ui.isBound, ui.awaitingFirstFrame, previewShown),
            animationSpec = motion.defaultEffectsSpec(),
        )
        val modeDip = remember { Animatable(0f) }
        var lastMode by remember { mutableStateOf(ui.mode) }
        LaunchedEffect(ui.mode) {
            if (ui.mode == lastMode) return@LaunchedEffect
            lastMode = ui.mode
            // With photo and video bound together the switch is instant; a dip would read as a glitch.
            if (currentUi.supportsSimultaneousVideo) return@LaunchedEffect
            modeDip.snapTo(0.45f)
            modeDip.animateTo(0f, motion.slowEffectsSpec())
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = maxOf(blackout, modeDip.value, captureBlink.value) }
                .background(colors.scrim),
        )
        StartingIndicator(visible = !ui.isBound, modifier = Modifier.align(Alignment.Center), delayMs = STARTING_SPINNER_DELAY_MS)

        Box(frame) {
            FocusRing(
                point = ui.focusPoint.takeUnless { focusGate || it == ignoredFocus },
                locked = ui.focusLocked,
                exposureBias = { liveUi.value.exposureBias },
                exposureEv = { liveUi.value.exposureEv },
                showExposure = ui.exposureSupported,
                labelRotation = iconRotation,
                onExposure = engine::setExposureBias,
            )
        }

        val scrim = colors.scrim
        val topFade = remember(scrim) { Brush.verticalGradient(listOf(scrim.copy(alpha = 0.55f), scrim.copy(alpha = 0f))) }
        val bottomFade = remember(scrim) { Brush.verticalGradient(listOf(scrim.copy(alpha = 0f), scrim.copy(alpha = 0.7f))) }
        val railFade = remember(scrim, isRtl) {
            val stops = listOf(scrim.copy(alpha = 0f), scrim.copy(alpha = 0.7f))
            Brush.horizontalGradient(if (isRtl) stops.reversed() else stops)
        }
        // A letterboxed preview leaves the bars on black, where a fade would only muddy the frame's edges.
        if (frameAspect == null) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(topFade),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (rail) 200.dp else 360.dp)
                    .background(bottomFade),
            )
        }
        if (rail) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(200.dp)
                    .background(railFade),
            )
        }

        RecordingAnnouncer(announcement)

        val topBar = @Composable {
            TopBar(
                ui = ui,
                recording = looksRecording,
                mic = mic,
                iconRotation = iconRotation,
                onClose = onDismiss,
                onFlash = {
                    haptics.perform(HapticEvent.Tick)
                    when (FlashPolicy.control(ui.mode, ui.hasPhotoFlash, ui.hasTorch)) {
                        FlashControl.Flash -> engine.setFlash(FlashPolicy.next(ui.flashMode))
                        FlashControl.Torch -> engine.setTorch(!ui.torchOn)
                        FlashControl.Hidden -> Unit
                    }
                },
                onMic = onRequestMic,
            )
        }

        val zoomControls = @Composable {
            ZoomControls(
                presets = presets,
                zoomRatio = { liveUi.value.zoomRatio },
                targetRatio = { presetTarget },
                iconRotation = iconRotation,
                showReadout = zoomGesture,
                dimmed = looksRecording && heldRecording,
                onSelect = { preset ->
                    haptics.perform(HapticEvent.Selection)
                    presetTarget = currentUi.clampZoom(preset.ratio)
                    engine.setZoomRatio(preset.ratio, animate = true)
                },
                onDragStart = {
                    barZoomBase = currentUi.zoomRatio
                    barTravel = 0f
                    zoomGesture = true
                },
                onDrag = { delta ->
                    val state = currentUi
                    barTravel += if (isRtl) -delta else delta
                    val range = ln(maxOf(state.maxZoom, state.minZoom) / state.minZoom)
                    zoomTo(barZoomBase * exp(barTravel / with(density) { ZoomBarFullRange.toPx() } * range))
                },
                onDragEnd = { zoomGesture = false },
            )
        }

        val carouselHide by animateFloatAsState(
            targetValue = if (looksRecording) 1f else 0f,
            animationSpec = if (reduceMotion) snap() else motion.defaultEffectsSpec(),
        )
        val carouselDrop = with(density) { CarouselHideDrop.toPx() }
        val modeCarousel = @Composable {
            if (modes.size > 1) {
                val labels = modes.map { modeLabel(it) }
                ModeCarousel(
                    state = carousel,
                    modes = modes,
                    labels = labels,
                    metrics = rememberModeSlotMetrics(labels),
                    selectedIndex = selectedIndex,
                    enabled = !looksRecording && !busy,
                    onSelect = { selectMode(it) },
                    onDragStart = ::modeDragStart,
                    onDrag = ::modeDrag,
                    onDragEnd = ::modeDragEnd,
                    modifier = Modifier.graphicsLayer {
                        alpha = 1f - carouselHide
                        translationY = carouselHide * carouselDrop
                    },
                )
            }
        }

        var shutterRowWidthPx by remember { mutableFloatStateOf(0f) }
        val lockOffset = with(density) {
            if (rail) {
                Offset(0f, (ShutterSize / 2 + RailGap + SideSlotSize / 2).toPx())
            } else {
                Offset((shutterRowWidthPx / 2 - SideSlotSize.toPx() / 2) * if (isRtl) 1f else -1f, 0f)
            }
        }
        val lockVisible = looksRecording && heldRecording && !keyHoldStarted
        val lockTarget = @Composable {
            LockTarget(
                visible = lockVisible,
                progress = { lockProgress },
                iconRotation = iconRotation,
            )
        }
        val lockHint = @Composable {
            LockHint(
                visible = lockVisible,
                direction = lockOffset,
                progress = { lockProgress },
                modifier = Modifier.absoluteOffset { IntOffset((lockOffset.x / 2).roundToInt(), (lockOffset.y / 2).roundToInt()) },
            )
        }
        val shutter = @Composable {
            ShutterButton(
                state = buttonState,
                holdEnabled = holdEnabled,
                busy = busy,
                enabled = ui.isBound || ui.isRecording,
                videoAmount = videoAmount,
                label = stringResource(
                    when (buttonState) {
                        CaptureButtonState.Photo -> MR.string.camera_shutter_photo
                        CaptureButtonState.Video -> MR.string.camera_shutter_video
                        else -> MR.string.camera_shutter_stop
                    }
                ),
                stateLabel = when {
                    busy -> stringResource(MR.string.camera_saving)
                    holdEnabled && buttonState == CaptureButtonState.Photo ->
                        stringResource(MR.string.camera_shutter_hold_hint)
                    else -> null
                },
                onTap = ::shutterTap,
                onHoldStart = {
                    holdZoomBase = currentUi.zoomRatio
                    startRecording(held = true)
                },
                lockOffset = lockOffset,
                onLockProgress = { lockProgress = it },
                onLockArmed = { haptics.perform(HapticEvent.Tick) },
                onLock = ::lockRecording,
                onHoldZoom = { fraction ->
                    if (currentUi.isRecording) {
                        val ceiling = minOf(currentUi.maxZoom, HOLD_ZOOM_CEILING).coerceAtLeast(holdZoomBase)
                        zoomTo(holdZoomBase * (ceiling / holdZoomBase).pow(fraction))
                    }
                },
                onHoldEnd = { if (heldRecording) stopRecording() },
            )
        }
        val startSlot = @Composable {
            Box(contentAlignment = Alignment.Center) {
                if (onOpenGallery != null) {
                    GalleryButton(
                        visible = !looksRecording,
                        enabled = !busy,
                        thumbnail = galleryThumbnail,
                        iconRotation = iconRotation,
                        onClick = {
                            haptics.perform(HapticEvent.Tick)
                            onOpenGallery()
                        },
                    )
                }
                lockTarget()
            }
        }
        val flip = @Composable {
            FlipLensButton(
                visible = ui.hasFrontLens && ui.hasBackLens,
                enabled = !looksRecording && !busy,
                hidden = looksRecording,
                lens = ui.lens,
                turns = lensTurns,
                iconRotation = iconRotation,
                onClick = ::flipLens,
            )
        }

        if (rail) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    topBar()
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp), contentAlignment = Alignment.Center) {
                    lockHint()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(RailGap),
                    ) {
                        flip()
                        shutter()
                        startSlot()
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!sideways) SnackbarHost(snackbar, modifier = Modifier.padding(horizontal = 16.dp))
                    zoomControls()
                    Spacer(Modifier.height(12.dp))
                    modeCarousel()
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                topBar()
                Spacer(Modifier.weight(1f))
                if (!sideways) SnackbarHost(snackbar, modifier = Modifier.padding(horizontal = 16.dp))
                zoomControls()
                Spacer(Modifier.height(16.dp))
                if (modes.size > 1) {
                    modeCarousel()
                    Spacer(Modifier.height(20.dp))
                }
                Box(
                    modifier = Modifier
                        .widthIn(max = ShutterRowMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = ShutterRowPadding)
                        .padding(bottom = 24.dp)
                        .onSizeChanged { shutterRowWidthPx = it.width.toFloat() },
                    contentAlignment = Alignment.Center,
                ) {
                    lockHint()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        startSlot()
                        shutter()
                        flip()
                    }
                }
            }
        }
        if (sideways) {
            // Laid out along the long edge, then turned with the icons so it reads upright in the user's hand.
            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.Center)
                    .requiredWidth(minOf(maxHeight - SidewaysSnackbarClearance, SidewaysSnackbarMaxWidth))
                    .graphicsLayer { rotationZ = iconRotation() },
            )
        }
    }
}

private fun List<ZoomPreset>.presetStep(ratio: Float): Int = count { it.ratio <= ratio * 1.001f }

@Composable
private fun ZoomControls(
    presets: List<ZoomPreset>,
    zoomRatio: () -> Float,
    targetRatio: () -> Float?,
    iconRotation: () -> Float,
    showReadout: Boolean,
    dimmed: Boolean,
    onSelect: (ZoomPreset) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    var readoutVisible by remember { mutableStateOf(false) }
    LaunchedEffect(showReadout) {
        if (showReadout) {
            readoutVisible = true
        } else {
            delay(ZOOM_READOUT_LINGER_MS)
            readoutVisible = false
        }
    }
    val alpha by animateFloatAsState(if (dimmed) 0.7f else 1f, motion.defaultEffectsSpec())
    Column(
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZoomReadout(visible = readoutVisible, zoomRatio = { targetRatio() ?: zoomRatio() }, rotation = iconRotation)
        ZoomPresetBar(
            presets = presets,
            zoomRatio = zoomRatio,
            targetRatio = targetRatio,
            iconRotation = iconRotation,
            onSelect = onSelect,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
        )
    }
}

@Composable
private fun ZoomReadout(visible: Boolean, zoomRatio: () -> Float, rotation: () -> Float) {
    val motion = MaterialTheme.motionScheme
    Box(Modifier.height(ZoomReadoutHeight), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motion.fastEffectsSpec()),
            exit = fadeOut(motion.defaultEffectsSpec()),
        ) {
            Text(
                text = stringResource(MR.string.camera_zoom_level, ZoomPresets.label(zoomRatio())),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .testTag(ZOOM_READOUT_TAG)
                    .graphicsLayer { rotationZ = rotation() }
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private val ZoomReadoutHeight = 32.dp

private val CameraError.messageRes: StringResource?
    get() = when (this) {
        CameraError.NoCamera -> null
        CameraError.BindFailed -> MR.string.camera_error_bind
        CameraError.CameraInUse -> MR.string.camera_error_in_use
        CameraError.Interrupted -> MR.string.camera_error_interrupted
        CameraError.InsufficientStorage -> MR.string.camera_error_storage
        is CameraError.PhotoFailed -> MR.string.camera_error_photo
        is CameraError.RecordingFailed -> MR.string.camera_error_recording
    }

/** Follows [targetDegrees] the short way round, so 270° → 0° turns 90° rather than 270° back. */
@Composable
internal fun animatedUprightRotation(targetDegrees: Float): State<Float> {
    val last = remember { floatArrayOf(targetDegrees) }
    val resolved = remember(targetDegrees) {
        val delta = ((targetDegrees - last[0]) % 360f + 540f) % 360f - 180f
        (last[0] + delta).also { last[0] = it }
    }
    return animateFloatAsState(
        resolved,
        if (LocalReduceMotion.current) snap() else MaterialTheme.motionScheme.defaultSpatialSpec(),
    )
}
