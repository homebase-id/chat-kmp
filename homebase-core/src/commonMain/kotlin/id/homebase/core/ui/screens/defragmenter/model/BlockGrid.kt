package id.homebase.core.ui.screens.defragmenter.model

/**
 * Compact 1-bit-per-block representation of the defragmenter grid.
 * 500k blocks ≈ 62 KB. KMP-friendly (no java.util.BitSet).
 *
 * Semantics: bit = 1 means the slot is filled (a live row), bit = 0 means a gap
 * (soft-deleted row awaiting hard delete).
 */
class BlockGrid(
    val totalBlocks: Int,
    private val bits: LongArray,
    initialGapCount: Int,
) {
    var gapCount: Int = initialGapCount
        private set

    fun isFilled(index: Int): Boolean {
        val word = bits[index ushr 6]
        return ((word ushr (index and 63)) and 1L) != 0L
    }

    fun setFilled(index: Int, filled: Boolean) {
        val wordIndex = index ushr 6
        val mask = 1L shl (index and 63)
        val word = bits[wordIndex]
        val currentlyFilled = (word and mask) != 0L
        if (filled == currentlyFilled) return
        if (filled) {
            bits[wordIndex] = word or mask
            gapCount--
        } else {
            bits[wordIndex] = word and mask.inv()
            gapCount++
        }
    }

    /**
     * Find the smallest index >= [fromIndex] that is a gap (bit = 0).
     * Returns -1 if none exists within the total block range.
     */
    fun nextGapFrom(fromIndex: Int): Int {
        if (fromIndex >= totalBlocks) return -1
        var i = maxOf(0, fromIndex)
        var wordIndex = i ushr 6
        val firstBit = i and 63
        var word = bits[wordIndex].inv()
        if (firstBit > 0) {
            word = word and (-1L shl firstBit)
        }
        while (wordIndex < bits.size) {
            if (word != 0L) {
                val bit = java_lang_Long_numberOfTrailingZeros(word)
                val found = (wordIndex shl 6) + bit
                return if (found < totalBlocks) found else -1
            }
            wordIndex++
            if (wordIndex >= bits.size) return -1
            word = bits[wordIndex].inv()
        }
        return -1
    }

    /**
     * Find the largest index <= [fromIndex] that is filled (bit = 1).
     * Returns -1 if none exists.
     */
    fun prevFilledFrom(fromIndex: Int): Int {
        if (fromIndex < 0) return -1
        var i = minOf(fromIndex, totalBlocks - 1)
        var wordIndex = i ushr 6
        val firstBit = i and 63
        var word = bits[wordIndex]
        if (firstBit < 63) {
            word = word and ((1L shl (firstBit + 1)) - 1L)
        }
        while (wordIndex >= 0) {
            if (word != 0L) {
                val bit = 63 - java_lang_Long_numberOfLeadingZeros(word)
                return (wordIndex shl 6) + bit
            }
            wordIndex--
            if (wordIndex < 0) return -1
            word = bits[wordIndex]
        }
        return -1
    }

    companion object {
        val EMPTY = BlockGrid(0, LongArray(0), 0)

        /**
         * Sized grid with every block marked as a gap. Used by the streaming
         * Analyze flow: the UI starts empty and flips bits to filled as the
         * scan head advances through the grid.
         */
        fun createEmpty(totalBlocks: Int): BlockGrid {
            val wordCount = (totalBlocks + 63) ushr 6
            return BlockGrid(totalBlocks, LongArray(wordCount), totalBlocks)
        }

        fun create(totalBlocks: Int, softDeletedIndices: IntArray): BlockGrid {
            val wordCount = (totalBlocks + 63) ushr 6
            val bits = LongArray(wordCount)
            for (w in 0 until wordCount) bits[w] = -1L
            val tail = totalBlocks and 63
            if (tail != 0 && wordCount > 0) {
                bits[wordCount - 1] = (1L shl tail) - 1L
            }
            for (idx in softDeletedIndices) {
                if (idx in 0 until totalBlocks) {
                    val wordIndex = idx ushr 6
                    val mask = 1L shl (idx and 63)
                    bits[wordIndex] = bits[wordIndex] and mask.inv()
                }
            }
            return BlockGrid(totalBlocks, bits, softDeletedIndices.size)
        }
    }
}

private fun java_lang_Long_numberOfTrailingZeros(v: Long): Int {
    if (v == 0L) return 64
    return v.countTrailingZeroBits()
}

private fun java_lang_Long_numberOfLeadingZeros(v: Long): Int {
    if (v == 0L) return 64
    return v.countLeadingZeroBits()
}
