package id.homebase.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatDecimalTest {
    @Test
    fun aNegativeValueCarriesOneSign() {
        assertEquals("-1.5", formatDecimal(-1.5, 1))
        assertEquals("-0.5", formatDecimal(-0.5, 1))
        assertEquals("0", formatDecimal(-0.01, 1), "rounds to nothing, so no sign")
        assertEquals("1.5", formatDecimal(1.5, 1))
    }
}
