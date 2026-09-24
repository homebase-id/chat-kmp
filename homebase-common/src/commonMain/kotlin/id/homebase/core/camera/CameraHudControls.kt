package id.homebase.core.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.formatHms
import id.homebase.resources.MR
import id.homebase.resources.camera_ae_af_lock
import id.homebase.resources.camera_close
import id.homebase.resources.camera_flash
import id.homebase.resources.camera_lens_back
import id.homebase.resources.camera_lens_front
import id.homebase.resources.camera_no_mic
import id.homebase.resources.camera_no_mic_a11y
import id.homebase.resources.camera_recording_a11y
import id.homebase.resources.camera_starting
import id.homebase.resources.camera_state_auto
import id.homebase.resources.camera_state_off
import id.homebase.resources.camera_state_on
import id.homebase.resources.camera_switch_lens
import id.homebase.resources.camera_torch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.time.Clock

internal const val CLOSE_TAG = "camera_close"
internal const val FLASH_TAG = "camera_flash"
internal const val FLIP_TAG = "camera_flip"
internal const val MODE_PHOTO_TAG = "camera_mode_photo"
internal const val MODE_VIDEO_TAG = "camera_mode_video"
internal const val TIMER_TAG = "camera_timer"
internal const val NO_MIC_TAG = "camera_no_mic"
internal const val FOCUS_RING_TAG = "camera_focus_ring"
internal const val EXPOSURE_TAG = "camera_exposure"
internal const val AE_LOCK_TAG = "camera_ae_af_lock"
internal const val LOCK_TAG = "camera_lock"
internal const val LOCK_HINT_TAG = "camera_lock_hint"
internal const val ANNOUNCER_TAG = "camera_announcer"
internal const val ZOOM_READOUT_TAG = "camera_zoom_readout"

internal val SideSlotSize = 56.dp

@Composable
internal fun TopBar(
    ui: CameraUiState,
    recording: Boolean,
    mic: MicPermission,
    iconRotation: () -> Float,
    onClose: () -> Unit,
    onFlash: () -> Unit,
    onMic: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            CameraCloseButton(onClick = onClose, iconRotation = iconRotation, modifier = Modifier.align(Alignment.CenterStart))
            RecordingTimer(
                visible = recording,
                startedAtMs = ui.recordingStartedAtMs.takeIf { ui.isRecording },
                modifier = Modifier.align(Alignment.Center),
            )
            FlashButton(
                ui = ui,
                recording = recording,
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
        AnimatedVisibility(
            visible = ui.focusLocked,
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleIn(initialScale = 0.9f),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            Text(
                text = stringResource(MR.string.camera_ae_af_lock),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag(AE_LOCK_TAG)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun hudIconButtonColors() = IconButtonDefaults.iconButtonColors(
    containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
    contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
internal fun CameraCloseButton(onClick: () -> Unit, iconRotation: () -> Float, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        colors = hudIconButtonColors(),
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.size(48.dp).testTag(CLOSE_TAG),
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(MR.string.camera_close),
            modifier = Modifier.graphicsLayer { rotationZ = iconRotation() },
        )
    }
}

@Composable
private fun FlashButton(
    ui: CameraUiState,
    recording: Boolean,
    iconRotation: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val control = FlashPolicy.control(ui.mode, ui.hasFlashUnit)
    AnimatedVisibility(
        visible = control == FlashControl.Torch || (control == FlashControl.Flash && !recording),
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        val torch = control == FlashControl.Torch
        val (icon, state) = when {
            torch && ui.torchOn -> Icons.Filled.FlashlightOn to MR.string.camera_state_on
            torch -> Icons.Filled.FlashlightOff to MR.string.camera_state_off
            ui.flashMode == FlashMode.Auto -> Icons.Filled.FlashAuto to MR.string.camera_state_auto
            ui.flashMode == FlashMode.On -> Icons.Filled.FlashOn to MR.string.camera_state_on
            else -> Icons.Filled.FlashOff to MR.string.camera_state_off
        }
        val stateText = stringResource(state)
        val active = (torch && ui.torchOn) || (control == FlashControl.Flash && ui.flashMode != FlashMode.Off)
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
            modifier = Modifier
                .size(48.dp)
                .testTag(FLASH_TAG)
                .semantics { stateDescription = stateText },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(if (torch) MR.string.camera_torch else MR.string.camera_flash),
                modifier = Modifier.graphicsLayer { rotationZ = iconRotation() },
            )
        }
    }
}

@Composable
private fun RecordingTimer(visible: Boolean, startedAtMs: Long?, modifier: Modifier = Modifier) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.8f),
        exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        // Reads 0:00 until the engine reports the start, and freezes on the last value while fading out after a stop.
        val elapsedSeconds by produceState(0L, startedAtMs) {
            val start = startedAtMs ?: return@produceState
            while (true) {
                value = (Clock.System.now().toEpochMilliseconds() - start).coerceAtLeast(0L) / 1000
                delay(250)
            }
        }
        val text = formatHms(elapsedSeconds * 1000)
        val a11y = stringResource(MR.string.camera_recording_a11y, text)
        val record = HomebaseTheme.extendedColors.cameraRecord
        val onRecord = HomebaseTheme.extendedColors.onCameraRecord
        Row(
            modifier = Modifier
                .testTag(TIMER_TAG)
                .background(record, CircleShape)
                .animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) { contentDescription = a11y },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dotModifier = Modifier.size(8.dp)
            if (LocalReduceMotion.current) {
                Box(dotModifier.background(onRecord, CircleShape))
            } else {
                val pulse = rememberInfiniteTransition()
                val dotAlpha by pulse.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                )
                Box(dotModifier.graphicsLayer { alpha = dotAlpha }.background(onRecord, CircleShape))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = onRecord,
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
            .minimumInteractiveComponentSize()
            .heightIn(min = 32.dp)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
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

/** Polite announcements for recording start/stop; the timer's own label changes every second and stays silent. */
@Composable
internal fun RecordingAnnouncer(text: String) {
    if (text.isEmpty()) return
    Box(
        Modifier
            .size(1.dp)
            .testTag(ANNOUNCER_TAG)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            },
    )
}

/** Visual only: TalkBack locks a recording through the shutter's long-press action instead. */
@Composable
internal fun LockTarget(visible: Boolean, progress: () -> Float, iconRotation: () -> Float) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val progress = progress()
    val engaged = progress >= LOCK_SNAP_FRACTION
    val scale by animateFloatAsState(if (engaged) 1.15f else 1f + progress * 0.1f, motion.fastSpatialSpec())
    val container by animateColorAsState(
        if (engaged) colors.onSurface else colors.scrim.copy(alpha = 0.32f + progress * 0.3f),
        motion.fastEffectsSpec(),
    )
    val content by animateColorAsState(if (engaged) colors.scrim else colors.onSurface, motion.fastEffectsSpec())
    Box(Modifier.size(SideSlotSize), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f),
            exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f),
        ) {
            Box(
                modifier = Modifier
                    .size(SideSlotSize)
                    .testTag(LOCK_TAG)
                    .clearAndSetSemantics { }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (engaged) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = iconRotation() },
                )
            }
        }
    }
}

@Composable
internal fun LockHint(visible: Boolean, direction: Offset, progress: () -> Float, modifier: Modifier = Modifier) {
    val degrees = (atan2(direction.y, direction.x) * 180f / PI.toFloat())
    val reduceMotion = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible && direction != Offset.Zero,
        enter = fadeIn(tween(durationMillis = 200, delayMillis = 150)),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        modifier = modifier,
    ) {
        val shimmer = if (reduceMotion) null else rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(900)))
        Row(
            modifier = Modifier
                .testTag(LOCK_HINT_TAG)
                .clearAndSetSemantics { }
                .graphicsLayer {
                    rotationZ = degrees
                    alpha = 1f - progress()
                },
        ) {
            repeat(HINT_CHEVRONS) { index ->
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            rotationZ = 90f
                            alpha = shimmer?.let { chevronAlpha(it.value, index) } ?: 0.8f
                        },
                )
            }
        }
    }
}

private const val HINT_CHEVRONS = 3

private fun chevronAlpha(shimmer: Float, index: Int): Float {
    val phase = ((shimmer * HINT_CHEVRONS) - index).let { if (it < 0f) it + HINT_CHEVRONS else it }
    return (1f - (phase / HINT_CHEVRONS)).coerceIn(0.35f, 1f)
}

@Composable
internal fun FlipLensButton(
    visible: Boolean,
    enabled: Boolean,
    hidden: Boolean,
    lens: CameraLens,
    turns: Float,
    iconRotation: () -> Float,
    onClick: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val motion = MaterialTheme.motionScheme
    val spin by animateFloatAsState(turns, motion.slowSpatialSpec())
    val fade = remember { Animatable(1f) }
    var lastTurns by remember { mutableStateOf(turns) }
    LaunchedEffect(turns) {
        if (turns == lastTurns) return@LaunchedEffect
        lastTurns = turns
        if (reduceMotion) {
            fade.snapTo(0f)
            fade.animateTo(1f, tween(150))
        }
    }
    val hide by animateFloatAsState(
        targetValue = if (hidden) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else motion.fastEffectsSpec(),
    )
    val density = LocalDensity.current.density
    val lensLabel = stringResource(if (lens == CameraLens.Front) MR.string.camera_lens_front else MR.string.camera_lens_back)
    if (!visible) {
        Spacer(Modifier.size(SideSlotSize))
        return
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = hudIconButtonColors(),
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier
            .size(SideSlotSize)
            .graphicsLayer {
                alpha = 1f - hide
                val scale = 1f - hide * 0.2f
                scaleX = scale
                scaleY = scale
            }
            .testTag(FLIP_TAG)
            .semantics { stateDescription = lensLabel },
    ) {
        Icon(
            imageVector = Icons.Outlined.Cameraswitch,
            contentDescription = stringResource(MR.string.camera_switch_lens),
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer {
                    rotationY = if (reduceMotion) 0f else spin
                    rotationZ = iconRotation()
                    alpha = fade.value
                    cameraDistance = 12f * density
                },
        )
    }
}

@Composable
internal fun StartingIndicator(visible: Boolean, modifier: Modifier = Modifier, delayMs: Long = 400) {
    // Held back briefly so a lens flip's quick rebind or an instant grant check doesn't flash a spinner.
    val show by produceState(false, visible) {
        value = false
        if (visible) {
            delay(delayMs)
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
internal fun FocusRing(
    point: Offset?,
    locked: Boolean,
    exposureBias: () -> Float,
    exposureEv: () -> Float,
    showExposure: Boolean,
) {
    val reduceMotion = LocalReduceMotion.current
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<Offset?>(null) }
    var widthPx by remember { mutableStateOf(0) }
    LaunchedEffect(point) {
        if (point == null) return@LaunchedEffect
        shown = point
        if (reduceMotion) {
            scale.snapTo(1f)
        } else {
            scale.snapTo(RING_POP_SCALE)
            scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 800f))
        }
    }
    LaunchedEffect(point, locked) {
        if (point == null) {
            alpha.animateTo(0f, tween(150))
            return@LaunchedEffect
        }
        // Every exposure nudge restarts the hold, so the ring stays up while it's being dragged.
        snapshotFlow { exposureBias() }.collectLatest {
            alpha.snapTo(1f)
            if (locked) return@collectLatest
            delay(RING_HOLD_MS)
            alpha.animateTo(0f, tween(300))
        }
    }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().onSizeChanged { widthPx = it.width }) {
        val at = shown ?: return@Box
        val density = LocalDensity.current
        val ringPx = with(density) { RingSize.toPx() }
        Box(
            Modifier
                .offset { IntOffset((at.x - ringPx / 2).roundToInt(), (at.y - ringPx / 2).roundToInt()) }
                .size(RingSize)
                .testTag(FOCUS_RING_TAG)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
                .border(2.dp, if (locked) colors.primary else colors.onSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(4.dp).background(if (locked) colors.primary else colors.onSurface, CircleShape))
        }
        if (showExposure) {
            val sliderWidthPx = with(density) { SliderWidth.toPx() }
            val sliderHeightPx = with(density) { SliderHeight.toPx() }
            val gapPx = with(density) { 8.dp.toPx() }
            val onEnd = at.x + ringPx / 2 + gapPx + sliderWidthPx <= widthPx
            val x = if (onEnd) at.x + ringPx / 2 + gapPx else at.x - ringPx / 2 - gapPx - sliderWidthPx
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), (at.y - sliderHeightPx / 2).roundToInt()) }
                    .size(SliderWidth, SliderHeight)
                    .testTag(EXPOSURE_TAG)
                    .clearAndSetSemantics { }
                    .graphicsLayer { this.alpha = alpha.value },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(2.dp, SliderHeight).background(colors.onSurface.copy(alpha = 0.6f), CircleShape))
                Box(Modifier.size(SliderWidth, 2.dp).background(colors.onSurface.copy(alpha = 0.6f), CircleShape))
                Icon(
                    imageVector = Icons.Outlined.WbSunny,
                    contentDescription = null,
                    tint = if (locked) colors.primary else colors.onSurface,
                    modifier = Modifier
                        .size(20.dp)
                        .offset { IntOffset(0, (-exposureBias() * (sliderHeightPx - 20.dp.toPx()) / 2).roundToInt()) }
                        .background(colors.scrim.copy(alpha = 0.4f), CircleShape),
                )
            }
            EvReadout(
                ev = exposureEv,
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), (at.y - sliderHeightPx / 2 - EvReadoutRise.toPx()).roundToInt()) }
                    .graphicsLayer { this.alpha = alpha.value },
            )
        }
    }
}

@Composable
private fun EvReadout(ev: () -> Float, modifier: Modifier = Modifier) {
    val text = formatEv(ev())
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier
            .testTag(EV_READOUT_TAG)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** "+0.7" / "−1.3"; null at 0 EV, where the readout hides. */
internal fun formatEv(ev: Float): String? {
    val tenths = (ev * 10f).roundToInt()
    if (tenths == 0) return null
    val magnitude = abs(tenths)
    return "${if (tenths > 0) "+" else "\u2212"}${magnitude / 10}.${magnitude % 10}"
}

internal const val EV_READOUT_TAG = "camera_ev_readout"

private val RingSize = 72.dp
private val SliderWidth = 24.dp
private val EvReadoutRise = 24.dp
private val SliderHeight = 112.dp
private const val RING_POP_SCALE = 1.4f
private const val RING_HOLD_MS = 1_500L
