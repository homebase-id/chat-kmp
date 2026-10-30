package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import kotlin.uuid.Uuid

/** How a click changes the selection: a plain click, Ctrl/Cmd, Shift, or Ctrl/Cmd+Shift. */
enum class FilesyncSelectMode { Only, Toggle, Range, AddRange }

/**
 * The selected rows of a folder. [anchor] is where a Shift range starts, [focus] the row the arrow keys move from;
 * both are rows in the order given, which is the order on screen.
 */
@Immutable
data class FilesyncSelection(val ids: Set<Uuid> = emptySet(), val anchor: Uuid? = null, val focus: Uuid? = null) {

    fun select(id: Uuid, mode: FilesyncSelectMode, order: List<Uuid>): FilesyncSelection = when (mode) {
        FilesyncSelectMode.Only -> FilesyncSelection(setOf(id), id, id)
        FilesyncSelectMode.Toggle -> FilesyncSelection(if (id in ids) ids - id else ids + id, id, id)
        FilesyncSelectMode.Range -> copy(ids = range(anchor ?: id, id, order), anchor = anchor ?: id, focus = id)
        FilesyncSelectMode.AddRange -> copy(ids = ids + range(anchor ?: id, id, order), anchor = anchor ?: id, focus = id)
    }

    /** The arrow keys: the next row by [by] from the focus; [extend] (Shift) selects from the anchor to it. */
    fun move(by: Int, extend: Boolean, order: List<Uuid>): FilesyncSelection {
        if (order.isEmpty()) return this
        val index = order.indexOf(focus)
        val next = order[if (index < 0) 0 else (index + by).coerceIn(0, order.lastIndex)]
        return if (extend) select(next, FilesyncSelectMode.Range, order) else select(next, FilesyncSelectMode.Only, order)
    }

    fun all(order: List<Uuid>): FilesyncSelection = copy(ids = order.toSet(), anchor = anchor ?: order.firstOrNull())

    /** What is left once [order] no longer holds some rows, as after a search or a reload. */
    fun keptIn(order: List<Uuid>): FilesyncSelection {
        val shown = order.toSet()
        return FilesyncSelection(ids intersect shown, anchor?.takeIf { it in shown }, focus?.takeIf { it in shown })
    }

    private fun range(from: Uuid, to: Uuid, order: List<Uuid>): Set<Uuid> {
        val a = order.indexOf(from)
        val b = order.indexOf(to)
        if (a < 0 || b < 0) return setOf(to)
        return order.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
    }
}
