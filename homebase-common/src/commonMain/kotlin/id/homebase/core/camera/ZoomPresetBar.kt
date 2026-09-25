package id.homebase.core.camera

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.camera_zoom_level
import id.homebase.resources.camera_zoom_preset_a11y
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal const val ZOOM_PRESET_TAG = "camera_zoom_preset_"
internal const val ZOOM_BAR_TAG = "camera_zoom_bar"
private val ZoomSlot = 48.dp

/**
 * The pill's side in px, leaving an even gap to [slotPx] (the slot matches the 48dp touch target). An odd gap is
 * halved twice, by the slot's centering and the button's touch-target inflation, and both round the same way,
 * so the pill sat a pixel or two low.
 */
internal fun evenlyInsetSide(slotPx: Int, pillPx: Float): Int {
    val inset = ((slotPx - pillPx) / 2f).roundToInt().coerceAtLeast(0)
    return slotPx - 2 * inset
}

@Composable
internal fun ZoomPresetBar(
    presets: List<ZoomPreset>,
    zoomRatio: () -> Float,
    targetRatio: () -> Float?,
    iconRotation: () -> Float,
    onSelect: (ZoomPreset) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (deltaPx: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (presets.size < 2) return
    val currentZoomRatio by rememberUpdatedState(zoomRatio)
    val currentTargetRatio by rememberUpdatedState(targetRatio)
    // Derived down to what the chips show, so a pinch recomposes per label step rather than per frame.
    val selection by remember(presets) {
        derivedStateOf {
            // A preset tap selects its chip at once; only pinches and bar drags show the live ratio.
            val ratio = currentTargetRatio() ?: currentZoomRatio()
            val atRatio = ZoomPresets.selected(ratio, presets)
            // The pill under the finger follows a pinch between presets.
            val active = atRatio ?: presets.lastOrNull { it.ratio <= ratio } ?: presets.firstOrNull()
            ZoomSelection(active, atRatio, if (atRatio == null) ZoomPresets.label(ratio) else null)
        }
    }
    val (active, atRatio, liveLabel) = selection
    val colors = MaterialTheme.colorScheme
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Row(
        modifier = modifier
            .testTag(ZOOM_BAR_TAG)
            .background(colors.scrim.copy(alpha = 0.5f), CircleShape)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { currentOnDragStart() },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                ) { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount)
                }
            }
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { preset ->
            val checked = preset == active
            val atPreset = atRatio == preset
            val number = if (checked && !atPreset && liveLabel != null) liveLabel else preset.label
            val text = stringResource(MR.string.camera_zoom_level, number)
            val description = stringResource(MR.string.camera_zoom_preset_a11y, preset.label)
            val pillSize by animateDpAsState(
                targetValue = if (checked) 44.dp else 36.dp,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            )
            Box(Modifier.size(ZoomSlot), contentAlignment = Alignment.Center) {
                ToggleButton(
                    checked = checked,
                    onCheckedChange = { onSelect(preset) },
                    // Circles in every state nest inside the track's round ends; the default checked square pokes out.
                    shapes = ToggleButtonDefaults.shapes(shape = CircleShape, pressedShape = CircleShape, checkedShape = CircleShape),
                    colors = ToggleButtonDefaults.toggleButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = colors.onSurface,
                        checkedContainerColor = colors.onSurface.copy(alpha = 0.2f),
                        checkedContentColor = colors.onSurface,
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .layout { measurable, _ ->
                            val side = evenlyInsetSide(ZoomSlot.roundToPx(), pillSize.toPx())
                            val placeable = measurable.measure(Constraints.fixed(side, side))
                            layout(side, side) { placeable.place(0, 0) }
                        }
                        .testTag(ZOOM_PRESET_TAG + preset.label)
                        .semantics { contentDescription = description },
                ) {
                    Text(
                        text = if (checked) text else number,
                        style = if (checked) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer { rotationZ = iconRotation() },
                    )
                }
            }
        }
    }
}

private data class ZoomSelection(val active: ZoomPreset?, val atRatio: ZoomPreset?, val liveLabel: String?)
