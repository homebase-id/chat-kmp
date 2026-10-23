package id.homebase.core.util

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MediumDateNoYearTest {
    private val originalLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun format(locale: Locale): String {
        Locale.setDefault(locale)
        val instant = Instant.fromEpochMilliseconds(
            LocalDate.of(2026, 5, 8).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        return formatMediumDateNoYear(instant)
    }

    @Test
    fun usIsMonthFirst() = assertEquals("May 8", format(Locale.US))

    @Test
    fun germanIsDayFirst() = assertEquals("8. Mai", format(Locale.GERMANY))

    @Test
    fun danishIsDayFirst() = assertEquals("8. maj", format(Locale.of("da", "DK")))
}
