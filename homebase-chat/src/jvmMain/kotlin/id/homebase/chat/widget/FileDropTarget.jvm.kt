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
import io.github.vinceglb.filekit.PlatformFile
import java.io.File
import java.net.URI

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragOverChanged: (Boolean) -> Unit,
    onFilesDropped: (List<PlatformFile>) -> Unit,
): Modifier {
    val dragOverChanged = rememberUpdatedState(onDragOverChanged)
    val filesDropped = rememberUpdatedState(onFilesDropped)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = dragOverChanged.value(true)

            override fun onExited(event: DragAndDropEvent) = dragOverChanged.value(false)

            override fun onEnded(event: DragAndDropEvent) = dragOverChanged.value(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragOverChanged.value(false)
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

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.droppedFiles(): List<PlatformFile> {
    val uris = filesList()?.let { runCatching { it.readFiles() }.getOrNull() } ?: return emptyList()
    return uris
        .mapNotNull { uri -> runCatching { File(URI(uri)) }.getOrNull() }
        .filter { it.isFile && it.canRead() }
        .map { PlatformFile(it) }
}
