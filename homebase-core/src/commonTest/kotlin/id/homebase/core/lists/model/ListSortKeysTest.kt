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

    @Test
    fun `between with null lower bound is strictly below the upper bound`() {
        // regression: the old position-0 clamp could return a key == the upper bound
        val hi = ListSortKeys.first()                 // "n"
        val mid = ListSortKeys.between(null, hi)
        assertTrue(mid < hi)
    }

    @Test
    fun `repeated insert-at-top stays strictly below and ordered`() {
        var hi = ListSortKeys.first()
        repeat(20) {
            val mid = ListSortKeys.between(null, hi)
            assertTrue(mid < hi)
            hi = mid
        }
    }

    @Test
    fun `equal or inverted bounds append after a instead of crashing`() {
        val k = ListSortKeys.first()
        assertTrue(ListSortKeys.between(k, k) > k)     // a == b → after(a)
        assertTrue(ListSortKeys.between("z", "a") > "z") // inverted → after("z")
    }

    @Test
    fun `after is always strictly greater than its input`() {
        for (k in listOf("a", "n", "z", "az", "n=")) {
            assertTrue(ListSortKeys.after(k) > k)
        }
    }
}
