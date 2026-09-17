package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AttachmentPopoverTest {

    @Test
    fun pickingATileRunsItsActionAndClosesThePopover() = runDesktopComposeUiTest(900, 560) {
        val picked = mutableListOf<String>()
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                val actions = attachmentActions(
                    onGalleryClick = {}, onFileClick = {}, onContactClick = {}, onLocationClick = {},
                    onEventClick = {}, onGroodleClick = {}, onDicesClick = {},
                    onPollClick = { picked += "poll" },
                )
                Box(Modifier.fillMaxSize()) {
                    AttachmentPopoverButton(
                        actions = actions,
                        alignToEnd = true,
                        onClick = { picked += "sheet" },
                        onPopoverDismissed = {},
                        modifier = Modifier.align(Alignment.BottomEnd).testTag("plus"),
                    ) { Text("+") }
                }
            }
        }

        onNodeWithTag("plus").performClick()
        onNodeWithTag("attachment_poll")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertTextEquals("Poll")
            .performClick()
        waitForIdle()

        assertEquals(listOf("poll"), picked)
        onNodeWithTag("attachment_poll").assertDoesNotExist()
    }
}
