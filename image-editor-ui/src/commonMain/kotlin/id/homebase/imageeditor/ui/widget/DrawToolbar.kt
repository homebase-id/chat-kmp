package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import id.homebase.core.widget.connectedButtonShapes
import id.homebase.imageeditor.core.draw.BrushType
import id.homebase.imageeditor.ui.Res
import id.homebase.imageeditor.ui.draw_action_back
import id.homebase.imageeditor.ui.draw_action_redo
import id.homebase.imageeditor.ui.draw_action_reset
import id.homebase.imageeditor.ui.draw_action_save
import id.homebase.imageeditor.ui.draw_action_undo
import id.homebase.imageeditor.ui.draw_brush_blur
import id.homebase.imageeditor.ui.draw_brush_highlighter
import id.homebase.imageeditor.ui.draw_brush_pen
import id.homebase.imageeditor.ui.draw_title
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawTopBar(
    onBack: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = { Text(text = stringResource(Res.string.draw_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.draw_action_back),
                )
            }
        },
        actions = {
            IconButton(onClick = onSave, enabled = saveEnabled) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(Res.string.draw_action_save),
                )
            }
        },
    )
}

@Composable
fun DrawBottomBar(
    selectedBrush: BrushType,
    canUndo: Boolean,
    canRedo: Boolean,
    colorPosition: Float,
    currentColorArgb: Int,
    onBrushSelected: (BrushType) -> Unit,
    onColorPositionChange: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HsvColorSlider(
            position = colorPosition,
            onPositionChange = onColorPositionChange,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(Res.string.draw_action_undo),
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = stringResource(Res.string.draw_action_redo),
                )
            }
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                brushes.forEachIndexed { index, (brush, icon, label) ->
                    BrushIconButton(
                        selected = selectedBrush == brush,
                        icon = icon,
                        contentDescription = stringResource(label),
                        shapes = connectedButtonShapes(index, brushes.size),
                        onClick = { onBrushSelected(brush) },
                    )
                }
            }
            ColorPreview(currentColorArgb)
            AssistChip(
                onClick = onReset,
                label = { Text(text = stringResource(Res.string.draw_action_reset)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

private val brushes = listOf(
    Triple(BrushType.Pen, Icons.Default.Edit, Res.string.draw_brush_pen),
    Triple(BrushType.Highlighter, Icons.Default.BorderColor, Res.string.draw_brush_highlighter),
    Triple(BrushType.Blur, Icons.Default.BlurOn, Res.string.draw_brush_blur),
)

@Composable
private fun BrushIconButton(
    selected: Boolean,
    icon: ImageVector,
    contentDescription: String,
    shapes: ToggleButtonShapes,
    onClick: () -> Unit,
) {
    FilledIconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        shapes = IconToggleButtonShapes(
            shape = shapes.shape,
            pressedShape = shapes.pressedShape,
            checkedShape = shapes.checkedShape,
        ),
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

@Composable
private fun ColorPreview(argb: Int) {
    val color = Color(argb.toLong() and 0xFFFFFFFFL)
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(color, shape = CircleShape)
            .padding(2.dp),
    )
}
