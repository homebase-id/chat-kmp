package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.PreferencesSettings
import id.homebase.core.settings.UserPreferences
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The box never latches open: whatever the release did, the row has to come back to rest,
 * otherwise the conversation stays permanently shifted with its action reveal showing.
 */
@OptIn(ExperimentalTestApi::class)
class SwipeRevealBoxGestureTest {

    private val rowTag = "swipe-row"
    private val revealTag = "swipe-reveal"

    private val koin = koinApplication {
        modules(
            module {
                single {
                    UserPreferences(
                        PreferencesSettings(Preferences.userRoot().node("/id/homebase/test"))
                    )
                }
            }
        )
    }

    @Composable
    private fun Harness(onSwipeRight: () -> Unit, slideOut: Boolean = false) {
        KoinIsolatedContext(koin) {
            MaterialTheme {
                SwipeRevealBox(
                    onSwipeRight = onSwipeRight,
                    onSwipeLeft = null,
                    commitThreshold = SwipeDistance.Fixed(20.dp),
                    maxOffset = SwipeDistance.Fixed(40.dp),
                    enabled = true,
                    slideOutOnSwipeRight = slideOut,
                    modifier = Modifier.fillMaxWidth(),
                    reveal = { Box(Modifier.testTag(revealTag).size(24.dp)) },
                ) {
                    Box(Modifier.testTag(rowTag).fillMaxWidth().height(64.dp))
                }
            }
        }
    }

    @Test
    fun aDragPastTheThresholdFiresOnReleaseAndSpringsBack() = runComposeUiTest {
        var fired = 0
        setContent { Harness(onSwipeRight = { fired++ }) }
        waitForIdle()
        onNodeWithTag(revealTag).assertDoesNotExist()

        onNodeWithTag(rowTag).performTouchInput { down(centerLeft + Offset(4f, 0f)) }
        onNodeWithTag(rowTag).performTouchInput { moveBy(Offset(40f, 0f)) }
        onNodeWithTag(rowTag).performTouchInput { moveBy(Offset(80f, 0f)) }
        waitForIdle()
        onNodeWithTag(revealTag).assertExists()
        assertEquals(0, fired, "the action must wait for the release")

        onNodeWithTag(rowTag).performTouchInput { up() }
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()

        assertEquals(1, fired)
        onNodeWithTag(revealTag).assertDoesNotExist()
    }

    @Test
    fun aShortDragSpringsBackWithoutFiring() = runComposeUiTest {
        var fired = 0
        setContent { Harness(onSwipeRight = { fired++ }) }
        waitForIdle()

        onNodeWithTag(rowTag).performTouchInput { down(centerLeft + Offset(4f, 0f)) }
        onNodeWithTag(rowTag).performTouchInput { moveBy(Offset(40f, 0f)) }
        onNodeWithTag(rowTag).performTouchInput { moveBy(Offset(8f, 0f)) }
        onNodeWithTag(rowTag).performTouchInput { up() }
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()

        assertEquals(0, fired)
        onNodeWithTag(revealTag).assertDoesNotExist()
    }

    @Test
    fun aCommittedSwipeThatRemovesTheRowSlidesOutInsteadOfSnappingBack() = runComposeUiTest {
        setContent { Harness(onSwipeRight = {}, slideOut = true) }
        waitForIdle()

        onNodeWithTag(rowTag).performTouchInput { down(centerLeft + Offset(4f, 0f)) }
        onNodeWithTag(rowTag).performTouchInput { moveBy(Offset(40f, 0f)) }
        onNodeWithTag(rowTag).performTouchInput { moveBy(Offset(80f, 0f)) }
        onNodeWithTag(rowTag).performTouchInput { up() }
        waitForIdle()
        mainClock.advanceTimeBy(400)
        waitForIdle()

        // Still held open: the list is expected to drop the row while it is off-screen.
        onNodeWithTag(revealTag).assertExists()

        // Nothing removed it, so it comes back rather than staying stuck aside.
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        onNodeWithTag(revealTag).assertDoesNotExist()
    }

    @Test
    fun theRowFollowsTheFingerUnderRtl() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Harness(onSwipeRight = {})
            }
        }
        waitForIdle()
        val atRest = onNodeWithTag(rowTag).getUnclippedBoundsInRoot().left

        onNodeWithTag(rowTag).performTouchInput { down(centerLeft + Offset(4f, 0f)) }
        onNodeWithTag(rowTag).performTouchInput { moveBy(Offset(40f, 0f)) }
        onNodeWithTag(rowTag).performTouchInput { moveBy(Offset(40f, 0f)) }
        waitForIdle()

        // Modifier.offset is RTL-aware and would mirror this into a leftward slide, so the
        // row would run away from the finger and uncover the wrong edge.
        assertTrue(
            onNodeWithTag(rowTag).getUnclippedBoundsInRoot().left > atRest,
            "a rightward drag must move the row right, RTL included",
        )
    }
}
