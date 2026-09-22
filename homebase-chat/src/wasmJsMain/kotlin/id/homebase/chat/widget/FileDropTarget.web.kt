@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.chat.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.browser.window
import org.w3c.dom.DataTransferItem
import org.w3c.dom.DragEvent
import org.w3c.dom.get
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener as EventListenerInterface

/**
 * Compose's own `Modifier.dragAndDropTarget` cannot carry this on wasmJs: measured against CMP
 * 1.10.3, an external file drag reaches `shouldStartDragAndDrop` and `onStarted` with the DOM
 * `DataTransfer` attached, but `onEntered`/`onMoved` arrive with a null one and `onDrop` is never
 * dispatched at all — so neither the hover preview nor the payload is reachable through it.
 */
@Composable
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragPreviewChanged: (FileDropPreview?) -> Unit,
    onFilesDropped: (List<PlatformFile>) -> Unit,
): Modifier {
    val previewChanged = rememberUpdatedState(onDragPreviewChanged)
    val filesDropped = rememberUpdatedState(onFilesDropped)
    val density = LocalDensity.current.density
    val bounds = remember { DropBounds() }

    DisposableEffect(enabled, density) {
        if (!enabled) return@DisposableEffect onDispose { }
        var depth = 0
        var showing = false

        fun clear() {
            depth = 0
            if (showing) {
                showing = false
                previewChanged.value(null)
            }
        }

        // Without preventDefault on both dragenter and dragover the browser never fires drop and
        // navigates to the file instead.
        val entered = EventListener {
            it.preventDefault()
            depth++
        }
        val moved = EventListener { event ->
            event.preventDefault()
            val over = bounds.covers(event.scenePosition(density))
            if (over && !showing) {
                // Anything without a file item — a dragged text selection, a link — is not ours.
                val items = event.items().map { it.describe() }
                if (items.any { it.kind == "file" }) {
                    showing = true
                    previewChanged.value(dropPreviewOf(items))
                }
            } else if (!over && showing) {
                showing = false
                previewChanged.value(null)
            }
        }
        // dragenter for the element being entered fires before dragleave for the one being left,
        // so only a zero depth means the drag really left the page.
        val left = EventListener {
            depth--
            if (depth <= 0) clear()
        }
        val dropped = EventListener { event ->
            event.preventDefault()
            // Only take a drop we actually offered to accept, so a dragged selection landing on
            // the chat doesn't report an empty attachment.
            val accept = showing && bounds.covers(event.scenePosition(density))
            clear()
            if (accept) {
                filesDropped.value(
                    event.items()
                        .filter { it.kind == "file" && !isDirectoryEntry(it) }
                        .mapNotNull { it.getAsFile() }
                        .map { PlatformFile(it) },
                )
            }
        }
        val ended = EventListener { clear() }

        window.addEventListener("dragenter", entered)
        window.addEventListener("dragover", moved)
        window.addEventListener("dragleave", left)
        window.addEventListener("drop", dropped)
        window.addEventListener("dragend", ended)
        onDispose {
            window.removeEventListener("dragenter", entered)
            window.removeEventListener("dragover", moved)
            window.removeEventListener("dragleave", left)
            window.removeEventListener("drop", dropped)
            window.removeEventListener("dragend", ended)
        }
    }

    return this.onGloballyPositioned { bounds.rect = it.boundsInWindow() }
}

// A plain holder, not a MutableState: the layout write has to be visible to a DOM callback
// immediately, and a snapshot write is only published when the next Compose frame commits — which
// on an idle web scene can be after the drag has already started.
private class DropBounds {
    var rect = Rect.Zero

    // An unmeasured rect accepts anywhere: the web scene defers layout until a frame is driven,
    // and on a freshly loaded page the drag itself drives the first one, so the opening dragover
    // would otherwise be swallowed.
    fun covers(point: Offset) = rect.isEmpty || rect.contains(point)
}

// offsetX/offsetY are CSS pixels against the canvas the event landed on, the same origin
// boundsInWindow reports against once scaled by the scene density.
private fun Event.scenePosition(density: Float): Offset {
    val event = this as DragEvent
    return Offset(event.offsetX.toFloat() * density, event.offsetY.toFloat() * density)
}

private fun Event.items(): List<DataTransferItem> {
    val list = (this as DragEvent).dataTransfer?.items ?: return emptyList()
    return (0 until list.length).mapNotNull { list[it] }
}

private fun DataTransferItem.describe() =
    DropItem(kind = kind, mimeType = type, isDirectory = isDirectoryEntry(this))

// A dragged directory is a "file" item with an empty type; only the entry tells them apart, and
// browsers withhold it until the drop.
@Suppress("UNUSED_PARAMETER")
private fun isDirectoryEntry(item: DataTransferItem): Boolean = js(
    """{
        var entry = item.webkitGetAsEntry ? item.webkitGetAsEntry() : null;
        return !!(entry && entry.isDirectory);
    }"""
)

// EventListener is a bare interface on wasm, so the lambda needs a JS wrapper.
@Suppress("UNUSED_PARAMETER")
private fun EventListener(handler: (Event) -> Unit): EventListenerInterface =
    js("(event) => { handler(event) }")
