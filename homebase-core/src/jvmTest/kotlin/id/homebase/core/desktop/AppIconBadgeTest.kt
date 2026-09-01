package id.homebase.core.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppIconBadgeTest {

    @Test
    fun `nothing is shown when there is nothing unread`() {
        assertNull(dockBadgeText(0))
        assertNull(overlayBadgeText(0))
        assertNull(dockBadgeText(-1))
        assertNull(overlayBadgeText(-1))
    }

    @Test
    fun `dock badge carries the exact total`() {
        assertEquals("1", dockBadgeText(1))
        assertEquals("126", dockBadgeText(126))
        assertEquals("12345", dockBadgeText(12345))
    }

    @Test
    fun `overlay badge caps once it stops fitting the icon`() {
        assertEquals("1", overlayBadgeText(1))
        assertEquals("99", overlayBadgeText(99))
        assertEquals("99+", overlayBadgeText(100))
        assertEquals("99+", overlayBadgeText(12345))
    }
}
