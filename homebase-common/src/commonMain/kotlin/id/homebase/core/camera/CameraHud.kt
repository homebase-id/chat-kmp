package id.homebase.core.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.TimeSource

internal const val PREVIEW_TAG = "camera_preview"

private const val HOLD_ZOOM_CEILING = 10f
private const val FLING_DP_PER_SECOND = 800f
// The shutter press has to be seen before the window closes on a fast capture.
private const val CAPTURE_FEEDBACK_MS = 180L
private const val ZOOM_READOUT_LINGER_MS = 600L
private val ZoomBarFullRange = 240.dp
private val ExposureTravel = 160.dp
internal val ShutterRowPadding = 32.dp
private val ShutterRowMaxWidth = 480.dp
private val RailGap = 24.dp
private val SidewaysSnackbarMaxWidth = 480.dp
private val SidewaysSnackbarClearance = 192.dp

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
    preview: @Composable (Modifier) -> Unit = {
        CameraPreview(engine, it, onLongPressFocus = { haptics.perform(HapticEvent.Confirm) })
    },
) {
    val ui by engine.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val reduceMotion = LocalReduceMotion.current
    val captureBlink = remember { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var heldRecording by remember { mutableStateOf(false) }
    var holdZoomBase by remember { mutableFloatStateOf(1f) }
    var lockProgress by remember { mutableFloatStateOf(0f) }
    var returnToPhotoAfterHold by remember { mutableStateOf(false) }
    var lensTurns by remember { mutableFloatStateOf(0f) }
    var announcement by remember { mutableStateOf("") }
    var zoomGesture by remember { mutableStateOf(false) }
    var barZoomBase by remember { mutableFloatStateOf(1f) }
    var barTravel by remember { mutableFloatStateOf(0f) }
    var focusGate by remember { mutableStateOf(false) }
    var ignoredFocus by remember { mutableStateOf<Offset?>(null) }
    var keyDown by remember { mutableStateOf(false) }
    var keyHoldStarted by remember { mutableStateOf(false) }
    var keyHoldJob by remember { mutableStateOf<Job?>(null) }
    val currentUi by rememberUpdatedState(ui)
    val currentMic by rememberUpdatedState(mic)
    val currentOnResult by rememberUpdatedState(onResult)
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
    val holdEnabled = allowedModes.allows(CaptureMode.Video)

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
            val message = error.messageRes ?: return@collect
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(getString(message))
        }
    }
    val selectedIndex = modes.indexOf(ui.mode).coerceAtLeast(0)
    LaunchedEffect(selectedIndex) { carousel.settleTo(selectedIndex, currentReduceMotion) }
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
        busy = true
        haptics.perform(HapticEvent.Confirm)
        val startedAt = currentUi.recordingStartedAtMs
        scope.launch {
            val elapsed = startedAt?.let { Clock.System.now().toEpochMilliseconds() - it } ?: 0L
            announcement = getString(MR.string.camera_recording_stopped, formatHms(elapsed.coerceAtLeast(0L)))
            val file = engine.stopRecording()
            busy = false
            stopping = false
            if (returnToPhotoAfterHold) {
                returnToPhotoAfterHold = false
                engine.setMode(CaptureMode.Photo)
            }
            deliver(file)
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
        if (busy || currentUi.isRecording) return false
        if (currentMic.needsAsking) {
            onRequestMic()
            return false
        }
        // Without a video use case bound next to the photo one, a hold rebinds to video first (the rebind is
        // synchronous on Android) and goes back to photo once the clip is saved.
        if (held && currentUi.mode == CaptureMode.Photo && !currentUi.supportsSimultaneousVideo) {
            returnToPhotoAfterHold = true
            engine.setMode(CaptureMode.Video)
        }
        heldRecording = held
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
            captureBlink.animateTo(0.9f, tween(durationMillis = 60))
            captureBlink.animateTo(0f, tween(durationMillis = 120))
        }
        scope.launch {
            val file = engine.takePhoto()
            val remaining = CAPTURE_FEEDBACK_MS - pressedAt.elapsedNow().inWholeMilliseconds
            if (file != null && remaining > 0) delay(remaining)
            busy = false
            deliver(file)
        }
    }

    fun shutterTap() {
        val state = CaptureButtonState.of(currentUi.mode, currentUi.isRecording, isRecordingLocked = !heldRecording)
        when (state.tapAction) {
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
        if (state.isRecording || busy || !state.hasFrontLens || !state.hasBackLens) return
        lensTurns += 180f
        haptics.perform(HapticEvent.Selection)
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
        if (currentUi.isRecording || busy || !allowedModes.allows(mode)) return false
        if (mode == currentUi.mode) return true
        if (haptic) haptics.perform(HapticEvent.Selection)
        engine.setMode(mode)
        return true
    }

    fun zoomTo(ratio: Float) {
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
        carousel.release(
            velocitySlotsPerSecond = -velocityPx * carouselDirection() / slotPx,
            flung = flung,
            reduceMotion = currentReduceMotion,
            commit = { index -> selectMode(modes[index], haptic = false) },
            onSlotChange = { haptics.perform(HapticEvent.Selection) },
        )
    }

    fun shutterKeyDown() {
        if (keyDown) return
        keyDown = true
        keyHoldStarted = false
        if (!holdEnabled || currentUi.isRecording || !currentUi.isBound) return
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
            override fun canDragMode() = modes.size > 1 && !currentUi.isRecording && !busy
            override fun onModeDragStart() = modeDragStart()
            override fun onModeDrag(deltaX: Float, slotPx: Float) = modeDrag(deltaX, slotPx)
            override fun onModeDragEnd(velocityX: Float, slotPx: Float) = modeDragEnd(velocityX, slotPx)
            override fun canDragExposure() = currentUi.exposureSupported && currentUi.focusPoint != null
            override fun onExposureDrag(deltaY: Float) {
                val travel = with(density) { ExposureTravel.toPx() }
                engine.setExposureBias(currentUi.exposureBias - deltaY / travel)
            }
        }
    }

    KeepScreenOnEffect(ui.isRecording)
    HardwareShutterEffect(onDown = ::shutterKeyDown, onUp = ::shutterKeyUp)

    if (!ui.isAvailable) {
        CameraUnavailablePane(onDismiss = onDismiss)
        return
    }

    val buttonState = CaptureButtonState.of(ui.mode, ui.isRecording, isRecordingLocked = !heldRecording)
    val uprightDegrees = deviceRotation.uprightIconDegrees(displayRotation)
    val iconRotation = animatedUprightRotation(uprightDegrees)
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
        // Gestures sit on the preview's parent, not the HUD's, so a quick double tap on the shutter isn't a flip.
        Box(
            Modifier
                .fillMaxSize()
                .testTag(PREVIEW_TAG)
                .pointerInput(previewGestures) { detectPreviewGestures(previewGestures) }
                .pointerInput(engine) { detectDoubleTapObserving { flipLens() } },
        ) {
            preview(Modifier.fillMaxSize())
        }

        val blackout by animateFloatAsState(
            targetValue = if (ui.isBound) 0f else 1f,
            animationSpec = motion.defaultEffectsSpec(),
        )
        val modeDip = remember { Animatable(0f) }
        var lastMode by remember { mutableStateOf(ui.mode) }
        LaunchedEffect(ui.mode) {
            if (ui.mode == lastMode) return@LaunchedEffect
            lastMode = ui.mode
            modeDip.snapTo(0.45f)
            modeDip.animateTo(0f, tween(durationMillis = 280))
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = maxOf(blackout, modeDip.value, captureBlink.value) }
                .background(colors.scrim),
        )
        StartingIndicator(visible = !ui.isBound, modifier = Modifier.align(Alignment.Center))

        FocusRing(
            point = ui.focusPoint.takeUnless { focusGate || it == ignoredFocus },
            locked = ui.focusLocked,
            exposureBias = ui.exposureBias,
            showExposure = ui.exposureSupported,
        )

        val scrim = colors.scrim
        val topFade = remember(scrim) { Brush.verticalGradient(listOf(scrim.copy(alpha = 0.55f), scrim.copy(alpha = 0f))) }
        val bottomFade = remember(scrim) { Brush.verticalGradient(listOf(scrim.copy(alpha = 0f), scrim.copy(alpha = 0.7f))) }
        val railFade = remember(scrim, isRtl) {
            val stops = listOf(scrim.copy(alpha = 0f), scrim.copy(alpha = 0.7f))
            Brush.horizontalGradient(if (isRtl) stops.reversed() else stops)
        }
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
                mic = mic,
                iconRotation = iconRotation,
                onClose = onDismiss,
                onFlash = {
                    haptics.perform(HapticEvent.Selection)
                    when (FlashPolicy.control(ui.mode, ui.hasFlashUnit)) {
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
                zoomRatio = ui.zoomRatio,
                iconRotation = iconRotation,
                showReadout = zoomGesture,
                dimmed = ui.isRecording && heldRecording,
                onSelect = { preset ->
                    haptics.perform(HapticEvent.Selection)
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
            targetValue = if (ui.isRecording) 1f else 0f,
            animationSpec = if (reduceMotion) snap() else motion.fastEffectsSpec(),
        )
        val modeCarousel = @Composable {
            if (modes.size > 1) {
                val labels = modes.map { modeLabel(it) }
                ModeCarousel(
                    state = carousel,
                    modes = modes,
                    labels = labels,
                    metrics = rememberModeSlotMetrics(labels),
                    selectedIndex = selectedIndex,
                    enabled = !ui.isRecording && !busy,
                    onSelect = { selectMode(it) },
                    onDragStart = ::modeDragStart,
                    onDrag = ::modeDrag,
                    onDragEnd = ::modeDragEnd,
                    modifier = Modifier.graphicsLayer {
                        alpha = 1f - carouselHide
                        translationY = carouselHide * size.height / 2
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
        val lockVisible = ui.isRecording && heldRecording && !keyHoldStarted
        val lockTarget = @Composable {
            LockTarget(
                visible = lockVisible,
                progress = lockProgress,
                iconRotation = iconRotation,
            )
        }
        val lockHint = @Composable {
            LockHint(
                visible = lockVisible,
                direction = lockOffset,
                progress = lockProgress,
                modifier = Modifier.absoluteOffset { IntOffset((lockOffset.x / 2).roundToInt(), (lockOffset.y / 2).roundToInt()) },
            )
        }
        val shutter = @Composable {
            ShutterButton(
                state = buttonState,
                holdEnabled = holdEnabled,
                busy = busy,
                enabled = ui.isBound || ui.isRecording,
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
        val flip = @Composable {
            FlipLensButton(
                visible = ui.hasFrontLens && ui.hasBackLens,
                enabled = !ui.isRecording && !busy,
                hidden = ui.isRecording,
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
                        lockTarget()
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
                        lockTarget()
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
                    .graphicsLayer { rotationZ = iconRotation },
            )
        }
    }
}

private fun List<ZoomPreset>.presetStep(ratio: Float): Int = count { it.ratio <= ratio * 1.001f }

@Composable
private fun ZoomControls(
    presets: List<ZoomPreset>,
    zoomRatio: Float,
    iconRotation: Float,
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
        ZoomReadout(visible = readoutVisible, zoomRatio = zoomRatio)
        ZoomPresetBar(
            presets = presets,
            zoomRatio = zoomRatio,
            iconRotation = iconRotation,
            onSelect = onSelect,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
        )
    }
}

@Composable
private fun ZoomReadout(visible: Boolean, zoomRatio: Float) {
    val motion = MaterialTheme.motionScheme
    Box(Modifier.height(ZoomReadoutHeight), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motion.fastEffectsSpec()),
            exit = fadeOut(motion.defaultEffectsSpec()),
        ) {
            Text(
                text = stringResource(MR.string.camera_zoom_level, ZoomPresets.label(zoomRatio)),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .testTag(ZOOM_READOUT_TAG)
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
internal fun animatedUprightRotation(targetDegrees: Float): Float {
    val last = remember { floatArrayOf(targetDegrees) }
    val resolved = remember(targetDegrees) {
        val delta = ((targetDegrees - last[0]) % 360f + 540f) % 360f - 180f
        (last[0] + delta).also { last[0] = it }
    }
    val rotation by animateFloatAsState(
        resolved,
        if (LocalReduceMotion.current) snap() else MaterialTheme.motionScheme.defaultSpatialSpec(),
    )
    return rotation
}
