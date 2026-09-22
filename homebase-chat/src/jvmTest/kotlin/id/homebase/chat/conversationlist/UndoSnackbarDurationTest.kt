package id.homebase.chat.conversationlist

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The undo snackbar carries an action label, and `showSnackbar` silently upgrades those to
 * `Indefinite`: build 1.3.1427 pinned it over the FAB forever and, because `showSnackbar`
 * serialises on a mutex, stalled every later snackbar on the screen behind it.
 */
@OptIn(ExperimentalTestApi::class)
class UndoSnackbarDurationTest {

    @Test
    fun anUndoSnackbarDismissesItself() = runComposeUiTest {
        val host = SnackbarHostState()
        setContent {
            LaunchedEffect(Unit) { host.showTimedSnackbar("Conversation archived", "Undo") }
            SnackbarHost(host)
        }
        waitForIdle()
        assertNotNull(host.currentSnackbarData, "snackbar never showed")

        mainClock.advanceTimeBy(10_000)
        waitForIdle()
        assertNull(host.currentSnackbarData, "snackbar was still up 10s later")
    }

    @Test
    fun theMaterialDefaultWouldNeverDismissIt() = runComposeUiTest {
        val host = SnackbarHostState()
        setContent {
            LaunchedEffect(Unit) {
                host.showSnackbar(message = "Conversation archived", actionLabel = "Undo")
            }
            SnackbarHost(host)
        }
        waitForIdle()

        mainClock.advanceTimeBy(60_000)
        waitForIdle()
        assertNotNull(host.currentSnackbarData)
    }
}
