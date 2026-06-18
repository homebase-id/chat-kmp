package id.homebase.core.ui.screens.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsDashboardTest {

    @Test
    fun trackerDeviceNeedsActivationTrackingAndFullPermissions() {
        assertFalse(isDashboard(activated = false, trackingEnabled = false, trackerAvailable = true, permissionsComplete = true, setupOverride = false))
        assertFalse(isDashboard(activated = true, trackingEnabled = false, trackerAvailable = true, permissionsComplete = true, setupOverride = false))
        assertFalse(isDashboard(activated = false, trackingEnabled = true, trackerAvailable = true, permissionsComplete = true, setupOverride = false))
        // Activated + tracking but only "while using the app" granted → stay on Setup
        // to finish the always/background grant (the reported bug).
        assertFalse(isDashboard(activated = true, trackingEnabled = true, trackerAvailable = true, permissionsComplete = false, setupOverride = false))
        // All conditions met → dashboard.
        assertTrue(isDashboard(activated = true, trackingEnabled = true, trackerAvailable = true, permissionsComplete = true, setupOverride = false))
    }

    @Test
    fun viewerDeviceGetsDashboardOnceActivated() {
        // Desktop/web: trackingEnabled and permissions are permanently false — must not strand on Setup.
        assertTrue(isDashboard(activated = true, trackingEnabled = false, trackerAvailable = false, permissionsComplete = false, setupOverride = false))
        assertFalse(isDashboard(activated = false, trackingEnabled = false, trackerAvailable = false, permissionsComplete = false, setupOverride = false))
    }

    @Test
    fun setupOverrideWins() {
        assertFalse(isDashboard(activated = true, trackingEnabled = true, trackerAvailable = true, permissionsComplete = true, setupOverride = true))
        assertFalse(isDashboard(activated = true, trackingEnabled = false, trackerAvailable = false, permissionsComplete = false, setupOverride = true))
    }
}
