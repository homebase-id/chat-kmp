package id.homebase.core.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.Haptics
import id.homebase.core.util.formatHms
import id.homebase.core.widget.connectedButtonShapes
import id.homebase.resources.MR
import id.homebase.resources.camera_close
import id.homebase.resources.camera_error_bind
import id.homebase.resources.camera_error_in_use
import id.homebase.resources.camera_error_interrupted
import id.homebase.resources.camera_error_photo
import id.homebase.resources.camera_error_recording
import id.homebase.resources.camera_error_storage
import id.homebase.resources.camera_flash_auto
import id.homebase.resources.camera_flash_off
import id.homebase.resources.camera_flash_on
import id.homebase.resources.camera_lens_back
import id.homebase.resources.camera_lens_front
import id.homebase.resources.camera_mode_photo
import id.homebase.resources.camera_mode_video
import id.homebase.resources.camera_no_mic
import id.homebase.resources.camera_no_mic_a11y
import id.homebase.resources.camera_recording_a11y
import id.homebase.resources.camera_saving
import id.homebase.resources.camera_shutter_hold_hint
import id.homebase.resources.camera_shutter_photo
import id.homebase.resources.camera_shutter_stop
import id.homebase.resources.camera_shutter_video
import id.homebase.resources.camera_starting
import id.homebase.resources.camera_switch_lens
import id.homebase.resources.camera_torch_off
import id.homebase.resources.camera_torch_on
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Clock
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal const val CLOSE_TAG = "camera_close"
internal const val FLASH_TAG = "camera_flash"
internal const val FLIP_TAG = "camera_flip"
internal const val MODE_PHOTO_TAG = "camera_mode_photo"
internal const val MODE_VIDEO_TAG = "camera_mode_video"
internal const val TIMER_TAG = "camera_timer"
internal const val NO_MIC_TAG = "camera_no_mic"
internal const val FOCUS_RING_TAG = "camera_focus_ring"

private const val HOLD_ZOOM_CEILING = 10f

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
    preview: @Composable (Modifier) -> Unit = { CameraPreview(engine, it) },
) {
    val ui by engine.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val captureFlash = remember { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var heldRecording by remember { mutableStateOf(false) }
    var holdZoomBase by remember { mutableFloatStateOf(1f) }
    var lensTurns by remember { mutableFloatStateOf(0f) }
    val currentUi by rememberUpdatedState(ui)
    val currentOnResult by rememberUpdatedState(onResult)

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

    fun deliver(file: PlatformFile?) {
        if (file != null) currentOnResult(file)
    }

    fun stopRecording() {
        if (stopping) return
        stopping = true
        busy = true
        haptics.perform(HapticEvent.LongPress)
        scope.launch {
            val file = engine.stopRecording()
            busy = false
            stopping = false
            deliver(file)
        }
    }

    // A recording the platform ended on its own (backgrounding, interruption, storage) still gets delivered.
    LaunchedEffect(engine) {
        var wasRecording = false
        engine.uiState.map { it.isRecording }.distinctUntilChanged().collect { recording ->
            if (wasRecording && !recording && !stopping) stopRecording()
            wasRecording = recording
        }
    }

    fun startRecording(held: Boolean): Boolean {
        if (busy || currentUi.isRecording) return false
        if (mic.needsAsking) {
            onRequestMic()
            return false
        }
        heldRecording = held
        haptics.perform(HapticEvent.LongPress)
        engine.startRecording(withAudio = mic.granted == true)
        return true
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        haptics.perform(HapticEvent.LongPress)
        scope.launch {
            captureFlash.snapTo(0.85f)
            captureFlash.animateTo(0f, tween(durationMillis = 260))
        }
        scope.launch {
            val file = engine.takePhoto()
            busy = false
            deliver(file)
        }
    }

    fun flipLens() {
        val state = currentUi
        if (state.isRecording || busy || !state.hasFrontLens || !state.hasBackLens) return
        lensTurns += 180f
        haptics.perform(HapticEvent.Selection)
        engine.setLens(if (state.lens == CameraLens.Back) CameraLens.Front else CameraLens.Back)
    }

    fun selectMode(mode: CaptureMode) {
        if (currentUi.isRecording || busy || mode == currentUi.mode) return
        haptics.perform(HapticEvent.Selection)
        engine.setMode(mode)
    }

    KeepScreenOnEffect(ui.isRecording)

    if (!ui.isAvailable) {
        CameraUnavailablePane(onDismiss = onDismiss)
        return
    }

    val buttonState = CaptureButtonState.of(ui.mode, ui.isRecording, isRecordingLocked = !heldRecording)
    val holdEnabled = allowedModes.allows(CaptureMode.Video) &&
        (ui.supportsSimultaneousVideo || ui.mode == CaptureMode.Video)
    val iconRotation = animatedUprightRotation(deviceRotation.uprightIconDegrees)
    val presets = remember(ui.minZoom, ui.maxZoom, ui.lensSwitchRatios) {
        ZoomPresets.available(ui.minZoom, ui.maxZoom, ui.lensSwitchRatios)
    }
    val colors = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize().background(colors.scrim)) {
        // Gestures sit on the preview's parent, not the HUD's, so a quick double tap on the shutter isn't a flip.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(engine) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) engine.setZoomRatio(currentUi.zoomRatio * zoom)
                    }
                }
                .pointerInput(engine) { detectDoubleTapObserving { flipLens() } },
        ) {
            preview(Modifier.fillMaxSize())
        }

        val blackout by animateFloatAsState(
            targetValue = if (ui.isBound) 0f else 1f,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        )
        Box(Modifier.fillMaxSize().alpha(blackout).background(colors.scrim))
        StartingIndicator(visible = !ui.isBound, modifier = Modifier.align(Alignment.Center))

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = captureFlash.value }
                .background(colors.onSurface),
        )

        FocusRing(point = ui.focusPoint)

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(160.dp)
                .background(Brush.verticalGradient(listOf(colors.scrim.copy(alpha = 0.55f), colors.scrim.copy(alpha = 0f)))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(300.dp)
                .background(Brush.verticalGradient(listOf(colors.scrim.copy(alpha = 0f), colors.scrim.copy(alpha = 0.6f)))),
        )

        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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

            Spacer(Modifier.weight(1f))

            SnackbarHost(snackbar, modifier = Modifier.padding(horizontal = 16.dp))

            ZoomPresetBar(
                presets = presets,
                zoomRatio = ui.zoomRatio,
                iconRotation = iconRotation,
                onSelect = { preset ->
                    haptics.perform(HapticEvent.Selection)
                    engine.setZoomRatio(preset.ratio, animate = true)
                },
            )
            Spacer(Modifier.height(16.dp))

            if (allowedModes == CameraModes.PhotoAndVideo) {
                ModeSwitch(
                    mode = ui.mode,
                    enabled = !ui.isRecording && !busy,
                    onSelect = ::selectMode,
                )
                Spacer(Modifier.height(20.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.size(56.dp))
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
                    onTap = {
                        when (buttonState.tapAction) {
                            CaptureAction.TakePhoto -> takePhoto()
                            CaptureAction.StartLockedRecording -> startRecording(held = false)
                            CaptureAction.StopRecording -> stopRecording()
                            else -> Unit
                        }
                    },
                    onHoldStart = {
                        holdZoomBase = currentUi.zoomRatio
                        startRecording(held = true)
                    },
                    onHoldZoom = { fraction ->
                        if (currentUi.isRecording) {
                            val ceiling = minOf(currentUi.maxZoom, HOLD_ZOOM_CEILING).coerceAtLeast(holdZoomBase)
                            engine.setZoomRatio(holdZoomBase * (ceiling / holdZoomBase).pow(fraction))
                        }
                    },
                    onHoldEnd = { if (heldRecording) stopRecording() },
                )
                FlipLensButton(
                    visible = ui.hasFrontLens && ui.hasBackLens,
                    enabled = !ui.isRecording && !busy,
                    lens = ui.lens,
                    turns = lensTurns,
                    iconRotation = iconRotation,
                    onClick = ::flipLens,
                )
            }
        }
    }
}

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

/** Observes without consuming, so the preview's own single-tap focus still runs underneath. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDoubleTapObserving(onDoubleTap: () -> Unit) {
    var lastUpMs = 0L
    var lastUp = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var multiTouch = false
        var up: Offset? = null
        var upMs = 0L
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) multiTouch = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                up = change.position
                upMs = change.uptimeMillis
                break
            }
        }
        val released = up ?: return@awaitEachGesture
        val tapped = !multiTouch && upMs - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
            (released - down.position).getDistance() < viewConfiguration.touchSlop
        if (!tapped) {
            lastUpMs = 0L
            return@awaitEachGesture
        }
        val isSecond = lastUpMs != 0L &&
            down.uptimeMillis - lastUpMs < viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastUp).getDistance() < viewConfiguration.touchSlop * 4
        if (isSecond) {
            lastUpMs = 0L
            onDoubleTap()
        } else {
            lastUpMs = upMs
            lastUp = released
        }
    }
}

/** Follows [targetDegrees] the short way round, so 270° → 0° turns 90° rather than 270° back. */
@Composable
internal fun animatedUprightRotation(targetDegrees: Float): Float {
    val last = remember { floatArrayOf(targetDegrees) }
    val resolved = remember(targetDegrees) {
        val delta = ((targetDegrees - last[0]) % 360f + 540f) % 360f - 180f
        (last[0] + delta).also { last[0] = it }
    }
    val rotation by animateFloatAsState(resolved, MaterialTheme.motionScheme.defaultSpatialSpec())
    return rotation
}

@Composable
private fun TopBar(
    ui: CameraUiState,
    mic: MicPermission,
    iconRotation: Float,
    onClose: () -> Unit,
    onFlash: () -> Unit,
    onMic: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            CameraCloseButton(onClick = onClose, iconRotation = iconRotation, modifier = Modifier.align(Alignment.CenterStart))
            RecordingTimer(
                startedAtMs = ui.recordingStartedAtMs.takeIf { ui.isRecording },
                modifier = Modifier.align(Alignment.Center),
            )
            FlashButton(
                ui = ui,
                iconRotation = iconRotation,
                onClick = onFlash,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        AnimatedVisibility(
            visible = ui.mode == CaptureMode.Video && mic.isDenied,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
        ) {
            NoMicChip(onClick = onMic)
        }
    }
}

@Composable
private fun hudIconButtonColors() = IconButtonDefaults.iconButtonColors(
    containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
    contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
internal fun CameraCloseButton(onClick: () -> Unit, iconRotation: Float, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        colors = hudIconButtonColors(),
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.size(48.dp).testTag(CLOSE_TAG),
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(MR.string.camera_close),
            modifier = Modifier.rotate(iconRotation),
        )
    }
}

@Composable
private fun FlashButton(ui: CameraUiState, iconRotation: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val control = FlashPolicy.control(ui.mode, ui.hasFlashUnit)
    AnimatedVisibility(
        visible = control != FlashControl.Hidden,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        val (icon, label) = when {
            control == FlashControl.Torch && ui.torchOn -> Icons.Filled.FlashlightOn to MR.string.camera_torch_on
            control == FlashControl.Torch -> Icons.Filled.FlashlightOff to MR.string.camera_torch_off
            ui.flashMode == FlashMode.Auto -> Icons.Filled.FlashAuto to MR.string.camera_flash_auto
            ui.flashMode == FlashMode.On -> Icons.Filled.FlashOn to MR.string.camera_flash_on
            else -> Icons.Filled.FlashOff to MR.string.camera_flash_off
        }
        val active = (control == FlashControl.Torch && ui.torchOn) ||
            (control == FlashControl.Flash && ui.flashMode != FlashMode.Off)
        IconButton(
            onClick = onClick,
            colors = if (active) {
                IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                hudIconButtonColors()
            },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(48.dp).testTag(FLASH_TAG),
        ) {
            Icon(imageVector = icon, contentDescription = stringResource(label), modifier = Modifier.rotate(iconRotation))
        }
    }
}

@Composable
private fun RecordingTimer(startedAtMs: Long?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = startedAtMs != null,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        val start = startedAtMs ?: 0L
        val elapsed by produceState(0L, start) {
            while (true) {
                value = (Clock.System.now().toEpochMilliseconds() - start).coerceAtLeast(0L)
                delay(250)
            }
        }
        val text = formatHms(elapsed)
        val a11y = stringResource(MR.string.camera_recording_a11y, text)
        val pulse = rememberInfiniteTransition()
        val dotAlpha by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        )
        Row(
            modifier = Modifier
                .testTag(TIMER_TAG)
                .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) { contentDescription = a11y },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(dotAlpha)
                    .background(MaterialTheme.colorScheme.onErrorContainer, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun NoMicChip(onClick: () -> Unit) {
    val a11y = stringResource(MR.string.camera_no_mic_a11y)
    Row(
        modifier = Modifier
            .testTag(NO_MIC_TAG)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.MicOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(MR.string.camera_no_mic),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ModeSwitch(mode: CaptureMode, enabled: Boolean, onSelect: (CaptureMode) -> Unit) {
    val entries = listOf(
        Triple(CaptureMode.Photo, MR.string.camera_mode_photo, MODE_PHOTO_TAG),
        Triple(CaptureMode.Video, MR.string.camera_mode_video, MODE_VIDEO_TAG),
    )
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f)
    Row(
        modifier = Modifier
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f), CircleShape)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        entries.forEachIndexed { index, (value, label, tag) ->
            ToggleButton(
                checked = value == mode,
                onCheckedChange = { onSelect(value) },
                enabled = enabled,
                shapes = connectedButtonShapes(index, entries.size),
                colors = ToggleButtonDefaults.toggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp),
                modifier = Modifier.height(40.dp).testTag(tag),
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun FlipLensButton(
    visible: Boolean,
    enabled: Boolean,
    lens: CameraLens,
    turns: Float,
    iconRotation: Float,
    onClick: () -> Unit,
) {
    val spin by animateFloatAsState(turns, MaterialTheme.motionScheme.slowSpatialSpec())
    val density = LocalDensity.current.density
    val lensLabel = stringResource(if (lens == CameraLens.Front) MR.string.camera_lens_front else MR.string.camera_lens_back)
    if (!visible) {
        Spacer(Modifier.size(56.dp))
        return
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = hudIconButtonColors(),
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier
            .size(56.dp)
            .testTag(FLIP_TAG)
            .semantics { stateDescription = lensLabel },
    ) {
        Icon(
            imageVector = Icons.Outlined.Cameraswitch,
            contentDescription = stringResource(MR.string.camera_switch_lens),
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer {
                    rotationY = spin
                    rotationZ = iconRotation
                    cameraDistance = 12f * density
                },
        )
    }
}

@Composable
private fun StartingIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    // Held back briefly so a lens flip's quick rebind doesn't flash a spinner.
    val show by produceState(false, visible) {
        value = false
        if (visible) {
            delay(400)
            value = true
        }
    }
    val label = stringResource(MR.string.camera_starting)
    AnimatedVisibility(visible = show, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        LoadingIndicator(
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(56.dp).semantics { contentDescription = label },
        )
    }
}

@Composable
private fun FocusRing(point: Offset?) {
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(point) {
        if (point == null) {
            alpha.animateTo(0f, tween(200))
            return@LaunchedEffect
        }
        shown = point
        scale.snapTo(1.5f)
        alpha.snapTo(1f)
        launch { scale.animateTo(1f, tween(220)) }
        delay(1200)
        alpha.animateTo(0.5f, tween(300))
    }
    val at = shown ?: return
    val ringPx = with(LocalDensity.current) { 72.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((at.x - ringPx / 2).roundToInt(), (at.y - ringPx / 2).roundToInt()) }
            .size(72.dp)
            .testTag(FOCUS_RING_TAG)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
            .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
    )
}
