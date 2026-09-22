package id.homebase.api.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepeatedErrorFilterTest {

    @Test
    fun acceptsUpToTheLimitThenStopsForever() {
        val filter = RepeatedErrorFilter(limit = 3)

        assertTrue(filter.accept("a"))
        assertTrue(filter.accept("a"))
        assertTrue(filter.accept("a"))
        assertFalse(filter.accept("a"))
        repeat(500) { assertFalse(filter.accept("a")) }
    }

    @Test
    fun countsEachKeyIndependently() {
        val filter = RepeatedErrorFilter(limit = 1)

        assertTrue(filter.accept("a"))
        assertFalse(filter.accept("a"))
        assertTrue(filter.accept("b"))
        assertFalse(filter.accept("b"))
    }
}
