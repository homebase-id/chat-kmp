package id.homebase.core.ui.screens.defragmenter.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockGridTest {

    @Test
    fun create_marksGapsAsEmpty() {
        val grid = BlockGrid.create(totalBlocks = 100, softDeletedIndices = intArrayOf(0, 50, 99))
        assertEquals(100, grid.totalBlocks)
        assertEquals(3, grid.gapCount)
        assertFalse(grid.isFilled(0))
        assertTrue(grid.isFilled(1))
        assertFalse(grid.isFilled(50))
        assertTrue(grid.isFilled(98))
        assertFalse(grid.isFilled(99))
    }

    @Test
    fun setFilled_maintainsGapCount() {
        val grid = BlockGrid.create(totalBlocks = 10, softDeletedIndices = intArrayOf(3, 7))
        assertEquals(2, grid.gapCount)

        grid.setFilled(3, true)
        assertEquals(1, grid.gapCount)
        assertTrue(grid.isFilled(3))

        grid.setFilled(3, true) // idempotent
        assertEquals(1, grid.gapCount)

        grid.setFilled(5, false)
        assertEquals(2, grid.gapCount)
        assertFalse(grid.isFilled(5))
    }

    @Test
    fun nextGapFrom_findsFirstGapAtOrAfterIndex() {
        val grid = BlockGrid.create(totalBlocks = 200, softDeletedIndices = intArrayOf(5, 64, 130))
        assertEquals(5, grid.nextGapFrom(0))
        assertEquals(5, grid.nextGapFrom(5))
        assertEquals(64, grid.nextGapFrom(6))
        assertEquals(64, grid.nextGapFrom(64))
        assertEquals(130, grid.nextGapFrom(65))
        assertEquals(-1, grid.nextGapFrom(131))
    }

    @Test
    fun prevFilledFrom_findsLastFilledAtOrBeforeIndex() {
        // All filled except 50, 60, 70.
        val grid = BlockGrid.create(totalBlocks = 100, softDeletedIndices = intArrayOf(50, 60, 70))
        assertEquals(99, grid.prevFilledFrom(99))
        assertEquals(69, grid.prevFilledFrom(70))
        assertEquals(59, grid.prevFilledFrom(60))
        assertEquals(49, grid.prevFilledFrom(50))
    }

    @Test
    fun simulatedCompaction_fillsAllGaps() {
        val total = 100
        val gaps = intArrayOf(2, 10, 20, 30, 40, 50, 60, 70, 80)
        val grid = BlockGrid.create(total, gaps)
        val initialGaps = grid.gapCount
        assertEquals(9, initialGaps)

        var from = grid.prevFilledFrom(total - 1)
        var to = grid.nextGapFrom(0)
        var moves = 0
        while (from > to && from >= 0 && to >= 0) {
            grid.setFilled(from, false)
            grid.setFilled(to, true)
            moves++
            from = grid.prevFilledFrom(from - 1)
            to = grid.nextGapFrom(to + 1)
        }
        // After compaction: all remaining filled blocks are contiguous from 0,
        // and the original initialGaps count was moved to the tail.
        assertEquals(initialGaps, moves)
        val filledCount = total - initialGaps
        for (i in 0 until filledCount) assertTrue(grid.isFilled(i), "expected filled at $i")
        for (i in filledCount until total) assertFalse(grid.isFilled(i), "expected gap at $i")
    }
}
