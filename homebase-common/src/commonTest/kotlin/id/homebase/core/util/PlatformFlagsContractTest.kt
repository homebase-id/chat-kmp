package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformFlagsContractTest {
    @Test
    fun exactlyOneFormFactorHolds() {
        assertEquals(1, listOf(isDesktop(), isMobile(), isWeb()).count { it })
    }

    @Test
    fun desktopOrWebIsTheUnionOfDesktopAndWeb() {
        assertEquals(isDesktop() || isWeb(), isDesktopOrWeb())
    }

    @Test
    fun nativeMobileImpliesMobile() {
        if (isNativeMobile()) assertTrue(isMobile())
    }

    @Test
    fun hostOsAgreesWithTheFormFactor() {
        when (hostOs) {
            HostOs.Android, HostOs.Ios -> assertTrue(isMobile())
            HostOs.Mac, HostOs.Windows, HostOs.Linux -> assertTrue(isDesktop())
            HostOs.Web -> assertTrue(isWeb())
        }
    }

    @Test
    fun platformReportsANonBlankName() {
        assertTrue(Platform.osName.isNotBlank())
        assertTrue(Platform.osVersion.isNotBlank())
    }
}
