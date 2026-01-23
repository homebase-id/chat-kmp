package id.homebase.homebasekmppoc.prototype.lib.core

import kotlinx.serialization.Serializable

/**
 * Represents an integer range with start and end values (inclusive)
 *
 * Ported from C# Odin.Core.IntRange
 */
@Serializable
data class IntRange(
    val start: Int,
    val end: Int
) {
    init {
        require(start <= end) { "Start must be less than or equal to end" }
    }

    /**
     * Check if a value is within this range (inclusive)
     */
    fun contains(value: Int): Boolean {
        return value in start..end
    }

    /**
     * Check if this range overlaps with another range
     */
    fun overlaps(other: IntRange): Boolean {
        return start <= other.end && end >= other.start
    }

    /**
     * Get the intersection of this range with another range
     */
    fun intersection(other: IntRange): IntRange? {
        val newStart = maxOf(start, other.start)
        val newEnd = minOf(end, other.end)
        return if (newStart <= newEnd) IntRange(newStart, newEnd) else null
    }

    fun validate() {
        if (start > end) {
            throw IllegalStateException("Start must be less than or equal to end")
        }
    }
}