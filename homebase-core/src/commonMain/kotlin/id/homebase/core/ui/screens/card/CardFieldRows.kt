@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class FieldDisplay(
    val onCard: Boolean,
    val onToggle: () -> Unit,
    val moveUp: (() -> Unit)? = null,
    val moveDown: (() -> Unit)? = null,
    val handle: Modifier? = null,
    val lifted: Boolean = false,
)

internal fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    if (from == to || from !in indices || to !in indices) this else toMutableList().also { it.add(to, it.removeAt(from)) }

// A drag reorders locally and commits once on release, so it is one write.
@Composable
internal fun ReorderableRows(
    items: List<CardContentItem>,
    gap: Dp,
    onReorder: (List<Uuid>) -> Unit,
    row: @Composable (item: CardContentItem, index: Int, count: Int, move: (Int) -> Unit, handle: Modifier, lifted: Boolean) -> Unit,
) {
    val ids = items.map { it.id }
    var order by remember(ids) { mutableStateOf(ids) }
    var dragging by remember { mutableStateOf<Uuid?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Uuid, Int>() }
    val byId = items.associateBy { it.id }

    fun commit() {
        val final = order
        dragging = null
        offset = 0f
        if (final != ids) onReorder(final)
    }

    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        order.forEachIndexed { index, id ->
            val item = byId[id] ?: return@forEachIndexed
            key(id) {
                val handle = Modifier.pointerInput(id) {
                    detectDragGestures(
                        onDragStart = { dragging = id; offset = 0f },
                        onDragEnd = { commit() },
                        onDragCancel = { order = ids; dragging = null; offset = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                            val at = order.indexOf(id)
                            val neighbour = if (offset > 0) order.getOrNull(at + 1) else order.getOrNull(at - 1)
                            val size = neighbour?.let { heights[it] }
                            if (neighbour != null && size != null && abs(offset) > size / 2f) {
                                val down = offset > 0
                                order = order.moved(at, if (down) at + 1 else at - 1)
                                offset += if (down) -size else size
                            }
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .onSizeChanged { heights[id] = it.height }
                        .zIndex(if (dragging == id) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging == id) offset else 0f },
                ) {
                    row(item, index, order.size, { delta -> onReorder(ids.moved(ids.indexOf(id), ids.indexOf(id) + delta)) }, handle, dragging == id)
                }
            }
        }
    }
}
