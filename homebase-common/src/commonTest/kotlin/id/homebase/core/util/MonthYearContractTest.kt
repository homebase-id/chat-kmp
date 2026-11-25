package id.homebase.core.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** On every platform's own formatter, in whatever locale the test runs in; MonthYearTest pins locales on the JVM. */
class MonthYearContractTest {
    private fun inDeviceZone(year: Int, month: Int, day: Int) = LocalDate(year, month, day).atStartOfDayIn(TimeZone.currentSystemDefault())

    @Test
    fun itNamesTheYearAndNotTheDay() {
        val label = formatMonthYear(inDeviceZone(2019, 3, 17))

        assertTrue("2019" in label, label)
        assertFalse("17" in label, label)
    }

    @Test
    fun theFirstOfAMonthIsThatMonthInTheDeviceZone() {
        assertTrue(formatMonthYear(inDeviceZone(2019, 3, 1)) != formatMonthYear(inDeviceZone(2019, 2, 28)))
        assertTrue(formatMonthYear(inDeviceZone(2019, 3, 1)) == formatMonthYear(inDeviceZone(2019, 3, 31)))
    }
}
