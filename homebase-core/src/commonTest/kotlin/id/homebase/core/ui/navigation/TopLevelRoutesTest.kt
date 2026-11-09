package id.homebase.core.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

    // A nav-bar app missing from isTopLevelRoute() loses the rail and bottom tabs, stranding the user.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun everyNavBarAppKeepsTheNavBar() = runComposeUiTest {
        lateinit var nav: NavHostController
        setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.ChatList) {
                composable<Route.ChatList> { Text("") }
                composable<Route.Calls> { Text("") }
                composable<Route.Feed> { Text("") }
                composable<Route.Moments> { Text("") }
                composable<Route.Vault> { Text("") }
                composable<Route.Filesync> { Text("") }
                composable<Route.Photobase> { Text("") }
                composable<Route.Location> { Text("") }
                composable<Route.ContactBook> { Text("") }
                composable<Route.Email> { Text("") }
                composable<Route.Home> { Text("") }
            }
        }
        val all = topLevelRoutesFor(
            callsAvailable = true,
            callsIconVisible = true,
            momentsVisible = true,
            vaultVisible = true,
            locationVisible = true,
            contactBookVisible = true,
            emailVisible = true,
            filesyncVisible = true,
            photobaseVisible = true,
        )
        all.forEach { topLevel ->
            runOnIdle { nav.navigate(topLevel.route) }
            waitForIdle()
            assertTrue(nav.currentDestination.isTopLevelRoute(), "${topLevel.route}")
        }
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
