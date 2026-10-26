package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class InputCapabilitiesTest {
    @Test
    fun uxPredicatesAreComplements() {
        assertEquals(!usesTouchUx(), usesPointerUx())
    }

    @Test
    fun resolvedCapabilitiesAreInternallyConsistent() {
        val c = inputCapabilities()
        if (c.primaryTouch) assertEquals(true, supportsTouchGestures())
        if (!c.primaryTouch) assertEquals(true, supportsHoverActions())
    }

    @Test
    fun nonWebTargetsMatchTheirPlatformAxis() {
        if (isWeb()) return
        assertEquals(isMobile(), usesTouchUx())
        assertEquals(isDesktop(), usesPointerUx())
    }
}
