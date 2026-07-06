package id.homebase.core.ui.screens.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsDashboardTest {

    @Test
    fun trackerDeviceNeedsActivationAndFullPermissions() {
        assertFalse(isDashboard(activated = false, trackerAvailable = true, permissionsComplete = true, setupOverride = false))
        // Activated but only "while using the app" granted (or both denied → permissionsComplete
        // false) → stay on Setup to grant access (the both-grants-denied → settings rule, #822).
        assertFalse(isDashboard(activated = true, trackerAvailable = true, permissionsComplete = false, setupOverride = false))
        // Activated + both grants → dashboard.
        assertTrue(isDashboard(activated = true, trackerAvailable = true, permissionsComplete = true, setupOverride = false))
    }

    @Test
    fun trackerWithGrantsButTrackingOffStillGetsDashboard() {
        // Bug #822: the landing keys on grants, NOT the "Track my location" toggle. A device with
        // both grants but tracking off must land on the dashboard (not Setup). isDashboard no longer
        // takes trackingEnabled, so both-grants → dashboard regardless of the toggle's state.
        assertTrue(isDashboard(activated = true, trackerAvailable = true, permissionsComplete = true, setupOverride = false))
    }

    @Test
    fun viewerDeviceGetsDashboardOnceActivated() {
        // Desktop/web: permissions are permanently false — must not strand on Setup.
        assertTrue(isDashboard(activated = true, trackerAvailable = false, permissionsComplete = false, setupOverride = false))
        assertFalse(isDashboard(activated = false, trackerAvailable = false, permissionsComplete = false, setupOverride = false))
    }

    @Test
    fun setupOverrideWins() {
        assertFalse(isDashboard(activated = true, trackerAvailable = true, permissionsComplete = true, setupOverride = true))
        assertFalse(isDashboard(activated = true, trackerAvailable = false, permissionsComplete = false, setupOverride = true))
    }
}
