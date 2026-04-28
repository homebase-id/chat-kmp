package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.imageeditor.core.AspectMode
import id.homebase.imageeditor.ui.crop_action_back
import id.homebase.imageeditor.ui.crop_action_flip
import id.homebase.imageeditor.ui.crop_action_lock_aspect
import id.homebase.imageeditor.ui.crop_action_lock_aspect_off
import id.homebase.imageeditor.ui.crop_action_redo
import id.homebase.imageeditor.ui.crop_action_reset
import id.homebase.imageeditor.ui.crop_action_rotate
import id.homebase.imageeditor.ui.crop_action_save
import id.homebase.imageeditor.ui.crop_action_undo
import id.homebase.imageeditor.ui.crop_aspect_16_9
import id.homebase.imageeditor.ui.crop_aspect_4_3
import id.homebase.imageeditor.ui.crop_aspect_free
import id.homebase.imageeditor.ui.crop_aspect_square
import id.homebase.imageeditor.ui.crop_title
import id.homebase.imageeditor.ui.Res
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropTopBar(
    onBack: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = { Text(text = stringResource(Res.string.crop_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.crop_action_back),
                )
            }
        },
        actions = {
            IconButton(onClick = onSave, enabled = saveEnabled) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(Res.string.crop_action_save),
                )
            }
        },
    )
}

@Composable
fun CropBottomBar(
    aspectMode: AspectMode,
    aspectLocked: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onAspectChange: (AspectMode) -> Unit,
    onAspectLockToggle: () -> Unit,
    onRotate90: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(Res.string.crop_action_undo),
            )
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Redo,
                contentDescription = stringResource(Res.string.crop_action_redo),
            )
        }
        IconButton(onClick = onRotate90) {
            Icon(
                imageVector = Icons.Default.Rotate90DegreesCw,
                contentDescription = stringResource(Res.string.crop_action_rotate),
            )
        }
        IconButton(onClick = onFlipHorizontal) {
            Icon(
                imageVector = Icons.Default.Flip,
                contentDescription = stringResource(Res.string.crop_action_flip),
            )
        }
        IconButton(onClick = onAspectLockToggle) {
            Crossfade(targetState = aspectLocked) { locked ->
                Icon(
                    imageVector = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = stringResource(
                        if (locked) Res.string.crop_action_lock_aspect_off
                        else Res.string.crop_action_lock_aspect
                    ),
                )
            }
        }
        AssistChip(
            onClick = onReset,
            label = { Text(text = stringResource(Res.string.crop_action_reset)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )

        AspectChip(
            label = stringResource(Res.string.crop_aspect_free),
            selected = aspectMode == AspectMode.Free,
            onClick = { onAspectChange(AspectMode.Free) },
        )
        AspectChip(
            label = stringResource(Res.string.crop_aspect_square),
            selected = aspectMode == AspectMode.Square,
            onClick = { onAspectChange(AspectMode.Square) },
        )
        AspectChip(
            label = stringResource(Res.string.crop_aspect_4_3),
            selected = aspectMode == AspectMode.R_4_3,
            onClick = { onAspectChange(AspectMode.R_4_3) },
        )
        AspectChip(
            label = stringResource(Res.string.crop_aspect_16_9),
            selected = aspectMode == AspectMode.R_16_9,
            onClick = { onAspectChange(AspectMode.R_16_9) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AspectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) },
    )
}
