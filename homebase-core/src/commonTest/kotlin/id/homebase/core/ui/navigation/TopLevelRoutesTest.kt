package id.homebase.core.ui.navigation

import id.homebase.core.ui.assets.Apps
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.resources.MR
import id.homebase.resources.nav_apps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopLevelRoutesTest {
    private fun routes(callsAvailable: Boolean, callsIconVisible: Boolean) = topLevelRoutesFor(
        callsAvailable = callsAvailable,
        callsIconVisible = callsIconVisible,
        momentsVisible = true,
        vaultVisible = false,
        locationVisible = false,
        contactBookVisible = false,
        emailVisible = false,
    )

    @Test
    fun callsSitsRightAfterChatsWhenAvailableAndVisible() {
        val routes = routes(callsAvailable = true, callsIconVisible = true)
        assertEquals(listOf(Route.ChatList, Route.Calls, Route.Feed, Route.Moments, Route.Home), routes.map { it.route })
    }

    @Test
    fun callsIsAbsentWhereCallingIsUnavailableOrTheIconIsHidden() {
        assertFalse(TopLevelRoute.Calls in routes(callsAvailable = false, callsIconVisible = true))
        assertFalse(TopLevelRoute.Calls in routes(callsAvailable = true, callsIconVisible = false))
        assertTrue(TopLevelRoute.Chat in routes(callsAvailable = false, callsIconVisible = false))
    }

    @Test
    fun theLastTabIsAppsWithTheAppsIcon() {
        val last = routes(callsAvailable = true, callsIconVisible = true).last()
        assertEquals(TopLevelRoute.Apps, last)
        assertEquals(Route.Home, last.route)
        assertEquals(MR.string.nav_apps, last.labelRes)
        assertEquals(HomebaseIcons.Apps, last.icon)
    }
}
