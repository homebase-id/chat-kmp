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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.camera_zoom_level
import id.homebase.resources.camera_zoom_preset_a11y
import org.jetbrains.compose.resources.stringResource

internal const val ZOOM_PRESET_TAG = "camera_zoom_preset_"
internal const val ZOOM_BAR_TAG = "camera_zoom_bar"

@Composable
internal fun ZoomPresetBar(
    presets: List<ZoomPreset>,
    zoomRatio: () -> Float,
    iconRotation: () -> Float,
    onSelect: (ZoomPreset) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (deltaPx: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (presets.size < 2) return
    val zoomRatio = zoomRatio()
    val atRatio = ZoomPresets.selected(zoomRatio, presets)
    // The pill under the finger follows a pinch between presets.
    val active = atRatio ?: presets.lastOrNull { it.ratio <= zoomRatio } ?: presets.firstOrNull()
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
            val number = if (checked && !atPreset) ZoomPresets.label(zoomRatio) else preset.label
            val text = stringResource(MR.string.camera_zoom_level, number)
            val description = stringResource(MR.string.camera_zoom_preset_a11y, preset.label)
            val pillSize by animateDpAsState(
                targetValue = if (checked) 44.dp else 36.dp,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            )
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                ToggleButton(
                    checked = checked,
                    onCheckedChange = { onSelect(preset) },
                    shapes = ToggleButtonDefaults.shapes(),
                    colors = ToggleButtonDefaults.toggleButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = colors.onSurface,
                        checkedContainerColor = colors.onSurface.copy(alpha = 0.2f),
                        checkedContentColor = colors.onSurface,
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .size(pillSize)
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
