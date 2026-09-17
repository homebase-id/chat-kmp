package id.homebase.chat.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class AttachmentPopoverTest {

    @Test
    fun pickingATileRunsItsActionAndCloses() = runDesktopComposeUiTest(900, 560) {
        var expanded by mutableStateOf(true)
        val picked = mutableListOf<String>()
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                val actions = attachmentActions(
                    onGalleryClick = { picked += "gallery"; expanded = false },
                    onFileClick = { picked += "file"; expanded = false },
                    onContactClick = { picked += "contact"; expanded = false },
                    onLocationClick = { picked += "location"; expanded = false },
                    onEventClick = { picked += "event"; expanded = false },
                    onGroodleClick = { picked += "groodle"; expanded = false },
                    onDicesClick = { picked += "dice"; expanded = false },
                    onPollClick = { picked += "poll"; expanded = false },
                )
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.BottomEnd).size(40.dp)) {
                        AttachmentPopover(expanded, actions, onDismissRequest = { expanded = false })
                    }
                }
            }
        }

        onNodeWithTag("attachment_poll")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertTextEquals("Poll")
            .performClick()
        waitForIdle()

        assertEquals(listOf("poll"), picked)
        assertFalse(expanded)
        onNodeWithTag("attachment_poll").assertDoesNotExist()
    }

    @Test
    fun anOutsideClickClosesWithoutReachingTheContentBelow() = runDesktopComposeUiTest(900, 560) {
        var expanded by mutableStateOf(true)
        var backgroundClicks = 0
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                val actions = attachmentActions({}, {}, {}, {}, {}, {}, {}, {})
                Box(Modifier.fillMaxSize().testTag("background").clickable { backgroundClicks++ }) {
                    Box(Modifier.align(Alignment.BottomEnd).size(40.dp)) {
                        AttachmentPopover(expanded, actions, onDismissRequest = { expanded = false })
                    }
                }
            }
        }

        onNodeWithTag("background").performMouseInput { click(Offset(40f, 40f)) }
        waitForIdle()

        assertFalse(expanded)
        assertEquals(0, backgroundClicks)
        onNodeWithTag("attachment_gallery").assertDoesNotExist()
    }
}
