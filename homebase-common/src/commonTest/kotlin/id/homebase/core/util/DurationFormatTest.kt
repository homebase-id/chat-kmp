package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class DurationFormatTest {
    @Test
    fun formats() {
        assertEquals("0:00", formatHms(0))
        assertEquals("0:59", formatHms(59_999))
        assertEquals("1:00", formatHms(60_000))
        assertEquals("10:05", formatHms(605_000))
        assertEquals("1:00:00", formatHms(3_600_000))
        assertEquals("1:02:03", formatHms(3_723_000))
    }

    @Test
    fun negativeClampsToZero() {
        assertEquals("0:00", formatHms(-5))
    }
}
