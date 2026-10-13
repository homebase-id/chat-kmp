package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class HostOsTest {
    @Test
    fun readsTheDesktopFamilyFromOsName() {
        assertEquals(HostOs.Mac, hostOsFromName("Mac OS X"))
        assertEquals(HostOs.Windows, hostOsFromName("Windows 11"))
        assertEquals(HostOs.Linux, hostOsFromName("Linux"))
    }
}
