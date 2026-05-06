package id.homebase.chat.widget.video

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
        assertEquals("0:00", formatDurationLabel(0))
        assertEquals("0:00", formatDurationLabel(999))
    }

    @Test
    fun secondsTransitions() {
        assertEquals("0:01", formatDurationLabel(1_000))
        assertEquals("0:09", formatDurationLabel(9_999))
        assertEquals("0:10", formatDurationLabel(10_000))
        assertEquals("0:59", formatDurationLabel(59_999))
    }

    @Test
    fun minuteTransitions() {
        assertEquals("1:00", formatDurationLabel(60_000))
        // Sub-second within the same minute floors down to that minute's :00
        assertEquals("1:00", formatDurationLabel(60_999))
        assertEquals("9:59", formatDurationLabel(599_999))
        assertEquals("10:00", formatDurationLabel(600_000))
    }

    @Test
    fun overflowMinutes_areExpressedInDecimal() {
        // We deliberately do NOT introduce H:MM:SS for >60 min. Bubble shows raw minutes.
        assertEquals("61:01", formatDurationLabel(3_661_000))
        assertEquals("120:00", formatDurationLabel(7_200_000))
    }
}
