package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import kotlin.uuid.Uuid

/** How a click changes the selection: a plain click, Ctrl/Cmd, Shift, or Ctrl/Cmd+Shift. */
enum class FilesyncSelectMode {
    Only, Toggle, Range, AddRange;

    companion object {
        /** [add] is Ctrl or Cmd held, [range] Shift. */
        fun of(add: Boolean, range: Boolean): FilesyncSelectMode = when {
            add && range -> AddRange
            add -> Toggle
            range -> Range
            else -> Only
        }
    }
}

/**
 * The selected rows of a folder. [anchor] is where a Shift range starts, [focus] the row the arrow keys move from;
 * both are rows in the order given, which is the order on screen.
 */
@Immutable
data class FilesyncSelection(val ids: Set<Uuid> = emptySet(), val anchor: Uuid? = null, val focus: Uuid? = null) {

    fun select(id: Uuid, mode: FilesyncSelectMode, order: List<Uuid>): FilesyncSelection {
        val start = anchor ?: id
        return when (mode) {
            FilesyncSelectMode.Only -> FilesyncSelection(setOf(id), id, id)
            FilesyncSelectMode.Toggle -> FilesyncSelection(if (id in ids) ids - id else ids + id, id, id)
            FilesyncSelectMode.Range -> FilesyncSelection(range(start, id, order), start, id)
            FilesyncSelectMode.AddRange -> FilesyncSelection(ids + range(start, id, order), start, id)
        }
    }

    /** The arrow keys: the next row by [by] from the focus; [extend] (Shift) selects from the anchor to it. */
    fun move(by: Int, extend: Boolean, order: List<Uuid>): FilesyncSelection {
        if (order.isEmpty()) return this
        val index = order.indexOf(focus)
        val next = order[if (index < 0) 0 else (index + by).coerceIn(0, order.lastIndex)]
        return select(next, if (extend) FilesyncSelectMode.Range else FilesyncSelectMode.Only, order)
    }

    fun all(order: List<Uuid>): FilesyncSelection =
        FilesyncSelection(order.toSet(), anchor ?: order.firstOrNull(), focus ?: order.firstOrNull())

    /** What is left once [order] no longer holds some rows, as after a search or a reload. */
    fun keptIn(order: List<Uuid>): FilesyncSelection {
        if (this == NONE) return this
        val shown = order.toSet()
        return FilesyncSelection(ids intersect shown, anchor?.takeIf { it in shown }, focus?.takeIf { it in shown })
    }

    companion object {
        val NONE = FilesyncSelection()
    }

    private fun range(from: Uuid, to: Uuid, order: List<Uuid>): Set<Uuid> {
        val a = order.indexOf(from)
        val b = order.indexOf(to)
        if (a < 0 || b < 0) return setOf(to)
        return order.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
    }
}
