package id.homebase.core.lists.model

import kotlin.test.Test
import kotlin.test.assertTrue

class ListSortKeysTest {
    @Test
    fun `first comes before after-first`() {
        val a = ListSortKeys.first()
        val b = ListSortKeys.after(a)
        assertTrue(a < b)
    }

    @Test
    fun `between produces a key strictly between two adjacent keys`() {
        val a = ListSortKeys.first()
        val b = ListSortKeys.after(a)
        val mid = ListSortKeys.between(a, b)
        assertTrue(a < mid && mid < b)
    }

    @Test
    fun `repeated between insertions stay ordered`() {
        var lo = ListSortKeys.first()
        var hi = ListSortKeys.after(lo)
        repeat(20) {
            val mid = ListSortKeys.between(lo, hi)
            assertTrue(lo < mid && mid < hi)
            hi = mid
        }
    }
}
