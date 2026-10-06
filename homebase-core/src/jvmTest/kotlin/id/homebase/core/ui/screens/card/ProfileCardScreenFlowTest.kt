package id.homebase.core.ui.screens.card

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProfileCardScreenFlowTest {

    @Test
    fun introToViewerAndBackThroughTheRealScreenAndViewModel() {
        val fixtures = ProfileCardViewModelTest()
        val chipFriends = hasContentDescription("Card for Friends", substring = true)
        runDesktopComposeUiTest(width = 824, height = 1784) {
            val host = ProfileCardViewModelTest.FakeHost()
            val source = ProfileCardViewModelTest.FakeSource(
                fixtures.profile +
                    fixtures.publicCardAttribute +
                    fixtures.circleCardAttribute(FRIENDS_CIRCLE_ID, "Friends", CardDesign.DOSSIER, 0),
            )
            val viewModel = ProfileCardViewModel(source) { host }
            setContent {
                HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                    ProfileCardScreen(viewModel = viewModel, onBack = {}, onEdit = {})
                }
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithContentDescription("Open the Friends card").fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodes(chipFriends).assertCountEquals(0)
            assertFalse(viewModel.uiState.value.viewing)

            onNodeWithContentDescription("Open the Friends card").performClick()
            waitUntil(timeoutMillis = 10_000) {
                onAllNodes(chipFriends).fetchSemanticsNodes().isNotEmpty()
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithContentDescription("Open the Friends card").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(viewModel.uiState.value.viewing)
            assertEquals(CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends"), viewModel.uiState.value.selectedAudience)
            assertEquals(CardDesign.DOSSIER, host.rendered.last().design)

            // Desktop wires no system back key; the viewer's Close runs the same leave() the BackHandler does.
            onNodeWithContentDescription("Close").performClick()
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithContentDescription("Open the Friends card").fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodes(chipFriends).assertCountEquals(0)
            assertFalse(viewModel.uiState.value.viewing)
        }
    }
}
