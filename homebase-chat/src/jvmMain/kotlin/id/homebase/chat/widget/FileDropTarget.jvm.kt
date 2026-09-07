package id.homebase.chat.widget

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import io.github.vinceglb.filekit.PlatformFile
import java.io.File
import java.net.URI

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragPreviewChanged: (FileDropPreview?) -> Unit,
    onFilesDropped: (List<PlatformFile>) -> Unit,
): Modifier {
    val previewChanged = rememberUpdatedState(onDragPreviewChanged)
    val filesDropped = rememberUpdatedState(onFilesDropped)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) =
                previewChanged.value(event.previewDraggedFiles())

            override fun onExited(event: DragAndDropEvent) = previewChanged.value(null)

            override fun onEnded(event: DragAndDropEvent) = previewChanged.value(null)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                previewChanged.value(null)
                filesDropped.value(event.droppedFiles())
                return true
            }
        }
    }
    if (!enabled) return this
    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { it.filesList() != null },
        target = target,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.filesList(): DragData.FilesList? =
    runCatching { dragData() }.getOrNull() as? DragData.FilesList

// X11 refuses transfer data until the drop, and a cross-process source may refuse it too.
@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.draggedFiles(): List<File> =
    filesList()?.let { runCatching { it.readFiles() }.getOrNull() }
        ?.mapNotNull { uri -> runCatching { File(URI(uri)) }.getOrNull() }
        .orEmpty()

private fun DragAndDropEvent.previewDraggedFiles(): FileDropPreview {
    val dragged = draggedFiles().ifEmpty { return FileDropPreview.Unreadable }
    val attachable = dragged.filter { it.isFile && it.canRead() }
    if (attachable.isEmpty()) return FileDropPreview.Rejected
    return FileDropPreview.Attachable(
        total = attachable.size,
        images = attachable.count {
            detectContentTypeFromExtensionOrHint(it.name).startsWith("image/")
        },
    )
}

private fun DragAndDropEvent.droppedFiles(): List<PlatformFile> =
    draggedFiles().filter { it.isFile && it.canRead() }.map { PlatformFile(it) }
