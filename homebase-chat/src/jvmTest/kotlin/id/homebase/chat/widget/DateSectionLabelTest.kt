package id.homebase.chat.widget

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DateSectionLabelTest {

    private val today = LocalDate(2026, 9, 9)

    @Test
    fun label_isTodayLabel_forToday() {
        val result = dateSectionLabel(
            messageDate = today,
            today = today,
            todayLabel = "Today",
            yesterdayLabel = "Yesterday",
        )
        assertEquals("Today", result)
    }

    @Test
    fun label_isYesterdayLabel_forYesterday() {
        val result = dateSectionLabel(
            messageDate = LocalDate(2026, 9, 8),
            today = today,
            todayLabel = "Today",
            yesterdayLabel = "Yesterday",
        )
        assertEquals("Yesterday", result)
    }

    @Test
    fun label_omitsYear_forDateEarlierThisYear() {
        val result = dateSectionLabel(
            messageDate = LocalDate(2026, 1, 1),
            today = today,
            todayLabel = "Today",
            yesterdayLabel = "Yesterday",
        )
        assertEquals("Jan 01", result)
    }

    @Test
    fun label_includesYear_forDateInPreviousYear() {
        val result = dateSectionLabel(
            messageDate = LocalDate(2025, 1, 1),
            today = today,
            todayLabel = "Today",
            yesterdayLabel = "Yesterday",
        )
        assertEquals("Jan 01, 2025", result)
    }
}
