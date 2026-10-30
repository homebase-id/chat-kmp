package id.homebase.core.ui.screens.filesync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class FilesyncSelectionTest {

    private val order = List(5) { Uuid.random() }
    private val a = order[0]
    private val b = order[1]
    private val c = order[2]
    private val d = order[3]
    private val e = order[4]

    @Test
    fun aPlainClickSelectsOnlyThatRow() {
        val selection = FilesyncSelection(setOf(a, b), a, b).select(c, FilesyncSelectMode.Only, order)
        assertEquals(FilesyncSelection(setOf(c), c, c), selection)
    }

    @Test
    fun toggleAddsAndRemovesAndMovesTheAnchor() {
        val added = FilesyncSelection().select(b, FilesyncSelectMode.Only, order).select(d, FilesyncSelectMode.Toggle, order)
        assertEquals(setOf(b, d), added.ids)
        assertEquals(d, added.anchor)
        assertEquals(setOf(d), added.select(b, FilesyncSelectMode.Toggle, order).ids)
    }

    @Test
    fun aRangeRunsFromTheAnchorEitherWayAndReplacesTheLastRange() {
        val start = FilesyncSelection().select(c, FilesyncSelectMode.Only, order)
        assertEquals(setOf(c, d, e), start.select(e, FilesyncSelectMode.Range, order).ids)
        val back = start.select(e, FilesyncSelectMode.Range, order).select(a, FilesyncSelectMode.Range, order)
        assertEquals(setOf(a, b, c), back.ids)
        assertEquals(c, back.anchor)
        assertEquals(a, back.focus)
    }

    @Test
    fun anAddedRangeKeepsWhatWasPicked() {
        val picked = FilesyncSelection().select(a, FilesyncSelectMode.Only, order).select(d, FilesyncSelectMode.Toggle, order)
        assertEquals(setOf(a, d, e), picked.select(e, FilesyncSelectMode.AddRange, order).ids)
    }

    @Test
    fun aRangeWithoutAnAnchorStartsAtTheRow() {
        assertEquals(FilesyncSelection(setOf(c), c, c), FilesyncSelection().select(c, FilesyncSelectMode.Range, order))
    }

    @Test
    fun aRangeFollowsTheOrderOnScreen() {
        val start = FilesyncSelection().select(b, FilesyncSelectMode.Only, order)
        val resorted = listOf(e, d, c, b, a)
        assertEquals(setOf(b, c, d), start.select(d, FilesyncSelectMode.Range, resorted).ids)
    }

    @Test
    fun theArrowKeysMoveTheFocusAndShiftExtendsFromTheAnchor() {
        val start = FilesyncSelection().move(1, extend = false, order)
        assertEquals(FilesyncSelection(setOf(a), a, a), start, "nothing focused starts at the top")
        val extended = start.move(1, extend = true, order).move(1, extend = true, order)
        assertEquals(setOf(a, b, c), extended.ids)
        assertEquals(setOf(a, b), extended.move(-1, extend = true, order).ids)
        assertEquals(FilesyncSelection(setOf(d), d, d), extended.move(1, extend = false, order))
        assertEquals(e, FilesyncSelection(setOf(e), e, e).move(1, extend = false, order).focus, "stops at the end")
        assertEquals(FilesyncSelection(), FilesyncSelection().move(1, extend = false, emptyList()))
    }

    @Test
    fun selectAllTakesEveryRowShown() {
        assertEquals(order.toSet(), FilesyncSelection().all(order).ids)
    }

    @Test
    fun rowsNoLongerShownLeaveTheSelection() {
        val selection = FilesyncSelection(setOf(a, c, e), a, e).keptIn(listOf(b, c, d))
        assertEquals(FilesyncSelection(setOf(c), null, null), selection)
    }
}
