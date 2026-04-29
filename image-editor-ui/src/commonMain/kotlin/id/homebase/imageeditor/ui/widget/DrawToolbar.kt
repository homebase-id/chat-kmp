package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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

        // History + brush row. Each brush is a circular IconButton; the
        // active one's container is filled with the primary colour so it
        // pops against the dark editor toolbar.
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
            BrushIconButton(
                selected = selectedBrush == BrushType.Pen,
                icon = Icons.Default.Edit,
                contentDescription = stringResource(Res.string.draw_brush_pen),
                onClick = { onBrushSelected(BrushType.Pen) },
            )
            BrushIconButton(
                selected = selectedBrush == BrushType.Highlighter,
                icon = Icons.Default.BorderColor,
                contentDescription = stringResource(Res.string.draw_brush_highlighter),
                onClick = { onBrushSelected(BrushType.Highlighter) },
            )
            BrushIconButton(
                selected = selectedBrush == BrushType.Blur,
                icon = Icons.Default.BlurOn,
                contentDescription = stringResource(Res.string.draw_brush_blur),
                onClick = { onBrushSelected(BrushType.Blur) },
            )
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

@Composable
private fun BrushIconButton(
    selected: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
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
