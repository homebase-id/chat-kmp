package id.homebase.core.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

@OptIn(ExperimentalTestApi::class)
class AppLinkTest {

    @Test
    fun fromSlug_acceptsHashSlashAndAnyCase() {
        assertEquals(AppLink.Filesync, AppLink.fromSlug("filesync"))
        assertEquals(AppLink.Filesync, AppLink.fromSlug("#filesync"))
        assertEquals(AppLink.Filesync, AppLink.fromSlug("#/FileSync/"))
        assertEquals(AppLink.Contacts, AppLink.fromSlug("contactbook"))
    }

    @Test
    fun fromSlug_rejectsUnknownAndFullRouteNames() {
        assertNull(AppLink.fromSlug(""))
        assertNull(AppLink.fromSlug("#"))
        assertNull(AppLink.fromSlug("nonsense"))
        assertNull(AppLink.fromSlug("#id.homebase.core.ui.navigation.Route.Filesync"))
    }

    @Test
    fun slugsAndRoutesAreUnique() {
        val slugs = AppLink.entries.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
        val routes = AppLink.entries.map { it.route::class }
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun slugIsTheRoutesSerialName() {
        for (link in AppLink.entries) {
            val json = Json.encodeToString(Route.serializer(), link.route)
            assertTrue(json.contains("\"type\":\"${link.slug}\""), "${link.name}: $json")
        }
    }

    @Test
    fun forBackStack_namesTheTopMostApp() = runComposeUiTest {
        lateinit var nav: NavHostController
        setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.ChatList) {
                composable<Route.ChatList> { Text("chats") }
                composable<Route.Filesync> { Text("filesync") }
                composable<Route.FilesyncRecent> { Text("recent") }
                composable<Route.Settings> { Text("settings") }
            }
        }
        waitForIdle()
        assertEquals(AppLink.Chat, AppLink.forBackStack(nav.currentBackStack.value))

        runOnIdle { nav.navigate(Route.Filesync) }
        waitForIdle()
        assertEquals(AppLink.Filesync, AppLink.forBackStack(nav.currentBackStack.value))

        runOnIdle { nav.navigate(Route.FilesyncRecent) }
        waitForIdle()
        assertEquals(AppLink.Filesync, AppLink.forBackStack(nav.currentBackStack.value))

        runOnIdle { nav.navigate(Route.Settings) }
        waitForIdle()
        assertEquals(AppLink.Settings, AppLink.forBackStack(nav.currentBackStack.value))
    }

    @Test
    fun forBackStack_emptyStackHasNoApp() {
        assertNull(AppLink.forBackStack(emptyList()))
    }
}
