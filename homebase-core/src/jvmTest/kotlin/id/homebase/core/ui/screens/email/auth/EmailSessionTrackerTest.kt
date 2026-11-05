package id.homebase.core.ui.screens.email.auth

import androidx.compose.material3.Text
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.email.EmailPreferences
import id.homebase.core.feed.newInMemoryJdbcDriver
import id.homebase.core.ui.navigation.Route
import id.homebase.core.ui.navigation.switchTab
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class EmailSessionTrackerTest {

    private class MutableClock(var current: Instant) : Clock {
        override fun now(): Instant = current
        operator fun plusAssign(delta: Duration) { current += delta }
    }

    private val tabs = listOf(Route.ChatList, Route.Email)

    private fun ComposeUiTest.host(prefs: EmailPreferences): NavHostController {
        lateinit var nav: NavHostController
        setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.ChatList) {
                composable<Route.ChatList> { Text("chats") }
                composable<Route.Email> { Text("email") }
                composable<Route.EmailSecrets> { Text("secrets") }
            }
            EmailSessionTracker(nav, prefs)
        }
        waitForIdle()
        return nav
    }

    private fun ComposeUiTest.act(block: () -> Unit) {
        runOnIdle(block)
        waitForIdle()
    }

    private fun prefs(clock: Clock): EmailPreferences {
        val driver = newInMemoryJdbcDriver()
        val db = DatabaseManager({ driver }, dispatcher = Dispatchers.Unconfined, readDispatcher = Dispatchers.Unconfined)
        return EmailPreferences(db, clock)
    }

    @Test
    fun returning_to_the_email_tab_after_the_idle_window_still_requires_unlock() = runComposeUiTest {
        val clock = MutableClock(Instant.fromEpochMilliseconds(1_000_000_000L))
        val prefs = prefs(clock)
        val nav = host(prefs)
        act { nav.switchTab(Route.Email, tabs) }
        act { nav.navigate(Route.EmailSecrets) }
        act { prefs.recordAuthSuccess() }
        assertTrue(prefs.isEmailScreenActive)

        act { nav.switchTab(Route.ChatList, tabs) }
        assertFalse(prefs.isEmailScreenActive)

        clock += 6.minutes
        act { nav.switchTab(Route.Email, tabs) }
        assertFalse(prefs.isEmailScreenActive)
        assertTrue(prefs.requiresUnlock())
    }

    @Test
    fun the_email_root_does_not_stop_the_idle_clock() = runComposeUiTest {
        val clock = MutableClock(Instant.fromEpochMilliseconds(1_000_000_000L))
        val prefs = prefs(clock)
        val nav = host(prefs)
        act { nav.switchTab(Route.Email, tabs) }
        act { nav.navigate(Route.EmailSecrets) }
        act { prefs.recordAuthSuccess() }
        act { nav.popBackStack() }

        clock += 4.minutes
        assertFalse(prefs.requiresUnlock())
        clock += 2.minutes
        assertTrue(prefs.requiresUnlock())
    }

    @Test
    fun staying_on_a_gated_screen_keeps_the_session() = runComposeUiTest {
        val clock = MutableClock(Instant.fromEpochMilliseconds(1_000_000_000L))
        val prefs = prefs(clock)
        val nav = host(prefs)
        act { nav.switchTab(Route.Email, tabs) }
        act { nav.navigate(Route.EmailSecrets) }
        act { prefs.recordAuthSuccess() }

        clock += 10.minutes
        assertFalse(prefs.requiresUnlock())
    }
}
