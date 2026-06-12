package id.homebase.core.ui.screens.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsDashboardTest {

    @Test
    fun trackerDeviceNeedsActivationAndTracking() {
        assertFalse(isDashboard(activated = false, trackingEnabled = false, trackerAvailable = true, setupOverride = false))
        assertFalse(isDashboard(activated = true, trackingEnabled = false, trackerAvailable = true, setupOverride = false))
        assertFalse(isDashboard(activated = false, trackingEnabled = true, trackerAvailable = true, setupOverride = false))
        assertTrue(isDashboard(activated = true, trackingEnabled = true, trackerAvailable = true, setupOverride = false))
    }

    @Test
    fun viewerDeviceGetsDashboardOnceActivated() {
        // Desktop/web: trackingEnabled is permanently false — must not strand on Setup.
        assertTrue(isDashboard(activated = true, trackingEnabled = false, trackerAvailable = false, setupOverride = false))
        assertFalse(isDashboard(activated = false, trackingEnabled = false, trackerAvailable = false, setupOverride = false))
    }

    @Test
    fun setupOverrideWins() {
        assertFalse(isDashboard(activated = true, trackingEnabled = true, trackerAvailable = true, setupOverride = true))
        assertFalse(isDashboard(activated = true, trackingEnabled = false, trackerAvailable = false, setupOverride = true))
    }
}
