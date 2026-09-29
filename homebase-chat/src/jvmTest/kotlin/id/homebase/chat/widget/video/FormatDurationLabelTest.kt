package id.homebase.chat.widget.video

import id.homebase.core.util.formatHms
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boundary table for the bubble's duration badge formatter. Values cross the
 * sub-second / sub-minute / two-digit-second / two-digit-minute thresholds where
 * formatting glitches typically hide.
 */
class FormatDurationLabelTest {

    @Test
    fun zeroAndSubSecond_formatAsZeroZero() {
        assertEquals("0:00", formatHms(0, showHours = false))
        assertEquals("0:00", formatHms(999, showHours = false))
    }

    @Test
    fun secondsTransitions() {
        assertEquals("0:01", formatHms(1_000, showHours = false))
        assertEquals("0:09", formatHms(9_999, showHours = false))
        assertEquals("0:10", formatHms(10_000, showHours = false))
        assertEquals("0:59", formatHms(59_999, showHours = false))
    }

    @Test
    fun minuteTransitions() {
        assertEquals("1:00", formatHms(60_000, showHours = false))
        // Sub-second within the same minute floors down to that minute's :00
        assertEquals("1:00", formatHms(60_999, showHours = false))
        assertEquals("9:59", formatHms(599_999, showHours = false))
        assertEquals("10:00", formatHms(600_000, showHours = false))
    }

    @Test
    fun overflowMinutes_areExpressedInDecimal() {
        // We deliberately do NOT introduce H:MM:SS for >60 min. Bubble shows raw minutes.
        assertEquals("61:01", formatHms(3_661_000, showHours = false))
        assertEquals("120:00", formatHms(7_200_000, showHours = false))
    }
}
