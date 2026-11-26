package id.homebase.core.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.homebase.core.ui.assets.Apps
import id.homebase.core.ui.screens.settings.AppsPage
import id.homebase.core.ui.screens.settings.appRoute
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.resources.MR
import id.homebase.resources.nav_apps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopLevelRoutesTest {
    private fun routes() = topLevelRoutesFor(
        momentsVisible = true,
        vaultVisible = false,
        locationVisible = false,
        contactBookVisible = false,
        emailVisible = false,
        everyApp = false,
        emailAvailable = true,
    )

    @Test
    fun theBarHasNoCallsEntry() {
        assertEquals(listOf(Route.ChatList, Route.Feed, Route.Moments, Route.Home), routes().map { it.route })
    }

    @Test
    fun aWideRailShowsEveryAvailableAppWhateverTheSwitches() {
        fun rail(emailAvailable: Boolean) = topLevelRoutesFor(
            momentsVisible = false, vaultVisible = false,
            locationVisible = false, contactBookVisible = false, emailVisible = false,
            filesyncVisible = false, photobaseVisible = false, feedVisible = false,
            everyApp = true, emailAvailable = emailAvailable,
        ).map { it.route }
        assertEquals(AppsPage.entries.map { it.appRoute }.filter { it != Route.WebDrop }.distinct() + Route.Home, rail(true))
        assertEquals(setOf(Route.Email), (rail(true) - rail(false).toSet()).toSet())
    }

    @Test
    fun theBarRunsInTheOrderOfSettingsAppsThenApps() {
        val every = topLevelRoutesFor(
            momentsVisible = true, vaultVisible = true,
            locationVisible = true, contactBookVisible = true, emailVisible = true,
            filesyncVisible = true, photobaseVisible = true, feedVisible = true,
            everyApp = false,
            emailAvailable = true,
        ).map { it.route }
        val settingsOrder = AppsPage.entries.map { it.appRoute }.distinct().filter { it in every }
        assertEquals(settingsOrder + Route.Home, every)
    }

    @Test
    fun feedFollowsItsSwitchWhileChatAndAppsAlwaysStay() {
        val routes = topLevelRoutesFor(
            momentsVisible = false,
            vaultVisible = false,
            locationVisible = false,
            contactBookVisible = false,
            emailVisible = false,
            feedVisible = false,
            everyApp = false,
            emailAvailable = true,
        )
        assertEquals(listOf(Route.ChatList, Route.Home), routes.map { it.route })
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
            momentsVisible = true,
            vaultVisible = true,
            locationVisible = true,
            contactBookVisible = true,
            emailVisible = true,
            filesyncVisible = true,
            photobaseVisible = true,
            everyApp = false,
            emailAvailable = true,
        )
        all.forEach { topLevel ->
            runOnIdle { nav.navigate(topLevel.route) }
            waitForIdle()
            assertTrue(nav.currentDestination.isTopLevelRoute(), "${topLevel.route}")
        }
    }

    @Test
    fun theLastTabIsAppsWithTheAppsIcon() {
        val last = routes().last()
        assertEquals(TopLevelRoute.Apps, last)
        assertEquals(Route.Home, last.route)
        assertEquals(MR.string.nav_apps, last.labelRes)
        assertEquals(HomebaseIcons.Apps, last.icon)
    }
}
