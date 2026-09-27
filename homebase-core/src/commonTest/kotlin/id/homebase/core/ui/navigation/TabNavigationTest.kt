package id.homebase.core.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TabNavigationTest {

    private val allTabs = listOf(
        Route.ChatList, Route.Feed, Route.Moments, Route.Vault,
        Route.Location, Route.ContactBook, Route.Home,
    )

    private val vaultHidden = allTabs - Route.Vault

    private fun ComposeUiTest.host(settingsAsDialog: Boolean = false): NavHostController {
        lateinit var nav: NavHostController
        setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.ChatList) {
                composable<Route.ChatList> { Text("chats") }
                composable<Route.Feed> { Text("feed") }
                composable<Route.Moments> { Text("moments") }
                composable<Route.MomentDetail> { Text("moment") }
                composable<Route.Vault> { Text("vault") }
                composable<Route.VaultSettings> { Text("vault settings") }
                composable<Route.Location> { Text("location") }
                composable<Route.ContactBook> { Text("contacts") }
                composable<Route.Home> { Text("home") }
                composable<Route.WebDrop> { Text("webdrop") }
                if (settingsAsDialog) {
                    dialog<Route.Settings> { Text("settings") }
                } else {
                    composable<Route.Settings> { Text("settings") }
                }
            }
        }
        waitForIdle()
        return nav
    }

    private fun ComposeUiTest.act(block: () -> Unit) {
        runOnIdle(block)
        waitForIdle()
    }

    private fun NavHostController.stack(): List<String> = currentBackStack.value
        .filter { it.destination !is NavGraph }
        .map { it.destination.route.orEmpty().substringBefore('/').substringBefore('?') }

    // C1: an add-on opened from Settings used to save Settings into the tab it was opened over.
    @Test
    fun settings_is_not_restored_when_its_tab_is_reselected() = runComposeUiTest {
        val nav = host()
        act { nav.switchTab(Route.Moments, allTabs) }
        act { nav.navigate(Route.Settings) }
        act { nav.openApp(Route.Location, allTabs) }
        assertEquals(listOf("conversation", "location"), nav.stack())

        act { nav.switchTab(Route.Moments, allTabs) }
        assertEquals(listOf("conversation", "moments"), nav.stack())
    }

    // C1 / desktop N3: the Settings pane is a dialog there, and must not come back either.
    @Test
    fun settings_pane_is_not_restored_when_its_tab_is_reselected() = runComposeUiTest {
        val nav = host(settingsAsDialog = true)
        act { nav.switchTab(Route.Moments, allTabs) }
        act { nav.navigate(Route.Settings) }
        act { nav.openApp(Route.Vault, allTabs) }
        assertEquals(listOf("conversation", "vault"), nav.stack())

        act { nav.switchTab(Route.Moments, allTabs) }
        assertEquals(listOf("conversation", "moments"), nav.stack())
    }

    // C2: a hidden add-on opens over Home instead of replacing it, so Back returns to Home.
    @Test
    fun hidden_add_on_opens_on_top_of_the_current_tab() = runComposeUiTest {
        val nav = host()
        act { nav.switchTab(Route.Home, vaultHidden) }
        act { nav.openApp(Route.Vault, vaultHidden) }
        assertEquals(listOf("conversation", "home", "vault"), nav.stack())
        assertEquals("home", nav.currentBackStack.value.currentTabRoot(vaultHidden)?.destination?.route)

        act { nav.popBackStack() }
        assertEquals(listOf("conversation", "home"), nav.stack())
    }

    // C2: WebDrop, never a tab, behaves like any other hidden add-on.
    @Test
    fun webdrop_opens_on_top_of_the_current_tab() = runComposeUiTest {
        val nav = host()
        act { nav.switchTab(Route.Home, allTabs) }
        act { nav.openApp(Route.WebDrop, allTabs) }
        assertEquals(listOf("conversation", "home", "webdrop"), nav.stack())
    }

    // C2: leaving the Home tab drops the hidden add-on rather than saving it under Home.
    @Test
    fun hidden_add_on_does_not_resurface_when_home_is_reselected() = runComposeUiTest {
        val nav = host()
        act { nav.switchTab(Route.Home, vaultHidden) }
        act { nav.openApp(Route.Vault, vaultHidden) }
        act { nav.switchTab(Route.Moments, vaultHidden) }
        assertEquals(listOf("conversation", "moments"), nav.stack())

        act { nav.switchTab(Route.Home, vaultHidden) }
        assertEquals(listOf("conversation", "home"), nav.stack())
    }

    // Tapping the lit Home item while a hidden add-on sits on it returns to Home.
    @Test
    fun reselecting_the_tab_under_a_hidden_add_on_returns_to_it() = runComposeUiTest {
        val nav = host()
        act { nav.switchTab(Route.Home, vaultHidden) }
        act { nav.openApp(Route.Vault, vaultHidden) }
        act { nav.switchTab(Route.Home, vaultHidden) }
        assertEquals(listOf("conversation", "home"), nav.stack())
    }

    // C6: a double tap on a launcher tile must not stack two copies.
    @Test
    fun opening_a_hidden_add_on_twice_keeps_one_copy() = runComposeUiTest {
        val nav = host()
        act { nav.switchTab(Route.Home, vaultHidden) }
        act { nav.openApp(Route.Vault, vaultHidden) }
        act { nav.openApp(Route.Vault, vaultHidden) }
        assertEquals(listOf("conversation", "home", "vault"), nav.stack())
    }

    // A visible tab keeps its own screens across a switch away and back.
    @Test
    fun a_tab_keeps_its_root_across_switches() = runComposeUiTest {
        val nav = host()
        act { nav.switchTab(Route.Vault, allTabs) }
        act { nav.switchTab(Route.Moments, allTabs) }
        act { nav.switchTab(Route.Vault, allTabs) }
        assertEquals(listOf("conversation", "vault"), nav.stack())
    }

    // Leaving an add-on for a conversation goes through the Chats tab, not a pop that discards it.
    @Test
    fun switching_to_chats_from_an_add_on_keeps_chats_at_the_root() = runComposeUiTest {
        val nav = host()
        act { nav.switchTab(Route.Vault, allTabs) }
        act { nav.navigate(Route.VaultSettings) }
        act { nav.switchTab(Route.ChatList, allTabs) }
        assertEquals(listOf("conversation"), nav.stack())

        act { nav.switchTab(Route.Vault, allTabs) }
        assertEquals(listOf("conversation", "vault"), nav.stack())
    }

    // Notification into a moment: Moments under the detail, whatever tab was showing.
    @Test
    fun moment_notification_lands_on_moments_under_the_detail() = runComposeUiTest {
        val nav = host()
        act { nav.switchTab(Route.Home, allTabs) }
        act { nav.openApp(Route.WebDrop, allTabs) }
        act {
            nav.openApp(Route.Moments, allTabs)
            nav.navigate(Route.MomentDetail(momentId = "m"))
        }
        assertEquals(listOf("conversation", "moments", "moment-detail"), nav.stack())

        act { nav.switchTab(Route.Home, allTabs) }
        assertEquals(listOf("conversation", "home"), nav.stack())
    }
}
