package id.homebase.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

class PlanStorageTest {
    private val mb = 1024L * 1024
    private val gb = mb * 1024
    private val tb = gb * 1024

    @Test
    fun aLittleUsedOnABigPlanIsInThePlanUnit() {
        assertEquals(
            PlanStorageText(percent = null, size = "500 GB", used = "0.1 GB", usedIsUnder = false, free = "499.9 GB"),
            planStorageText(8524 * mb / 100, 500 * gb),
        )
    }

    @Test
    fun usedThatRoundsToNothingIsUnderTheSmallestStep() {
        val text = planStorageText(2 * mb, tb)
        assertEquals("0.01 TB", text.used)
        assertEquals(true, text.usedIsUnder)
        assertEquals("1 TB", text.free)
    }

    @Test
    fun aPlanUnderTenOfItsUnitGetsTwoDecimals() {
        val text = planStorageText(tb * 29 / 100, tb)
        assertEquals(29, text.percent)
        assertEquals("0.29 TB", text.used)
        assertEquals("0.71 TB", text.free)
    }

    @Test
    fun itSays99UntilFullAnd100WhenFull() {
        assertEquals(99, planStorageText(4999 * gb / 10, 500 * gb).percent)
        assertEquals(100, planStorageText(500 * gb, 500 * gb).percent)
        val over = planStorageText(510 * gb, 500 * gb)
        assertEquals(100, over.percent)
        assertEquals("0 GB", over.free)
    }

    @Test
    fun nothingStoredIsZero() {
        val text = planStorageText(0, 60 * gb)
        assertEquals(0, text.percent)
        assertEquals("0 GB", text.used)
    }

    @Test
    fun aHalfUnitPlanKeepsItsHalf() {
        assertEquals("1.5 TB", planStorageText(0, tb * 3 / 2).size)
    }
}
