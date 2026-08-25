package id.homebase.chat.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.vinceglb.filekit.PlatformFile

@Composable
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragPreviewChanged: (FileDropPreview?) -> Unit,
    onFilesDropped: (List<PlatformFile>) -> Unit,
): Modifier = this
