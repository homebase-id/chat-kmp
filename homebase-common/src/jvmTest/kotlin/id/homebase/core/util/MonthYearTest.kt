package id.homebase.core.util

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MonthYearTest {
    private val originalLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun format(locale: Locale): String {
        Locale.setDefault(locale)
        val instant = Instant.fromEpochMilliseconds(
            LocalDate.of(2019, 3, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        return formatMonthYear(instant)
    }

    @Test
    fun us() = assertEquals("Mar 2019", format(Locale.US))

    @Test
    fun german() = assertEquals("März 2019", format(Locale.GERMANY))

    @Test
    fun danish() = assertEquals("mar. 2019", format(Locale.of("da", "DK")))
}
