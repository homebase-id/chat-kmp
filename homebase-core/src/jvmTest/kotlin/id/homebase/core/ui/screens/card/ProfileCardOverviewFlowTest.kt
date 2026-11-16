package id.homebase.core.ui.screens.card

import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.widget.ProvideOpenOverlays
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class ProfileCardOverviewFlowTest {

    private val friends = CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")

    private class Rig(val viewModel: ProfileCardViewModel) {
        var edits = 0
        var designs = 0
        var backs = 0
        val system = DirectNavigationEventInput()
    }

    private fun ComposeUiTest.screen(returningFromEditor: Boolean = false): Rig {
        val fixtures = ProfileCardViewModelTest()
        val wire = CardWireHarness()
        val repository = runBlocking { wire.profileRepository() }
        val source = ProfileCardViewModelTest.FakeSource(
            fixtures.profile.filterNot { it.type == ProfileAttributeTypes.PHOTO },
            cardRepository = CardRepository(ProfileRepositoryCardStore(repository)),
        ).apply {
            liveCards = { repository.loadAttributes() }
            circleList = listOf(CardCircle(FRIENDS_CIRCLE_ID, "Friends"))
        }
        // No host is started: the JVM card view needs a real webview, and the screen chrome is what is under test.
        val viewModel = ProfileCardViewModel(source) { error("the JVM WebView slot is not part of this flow") }
        if (returningFromEditor) viewModel.onLeftForEditor()
        val rig = Rig(viewModel)
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher().also { it.addInput(rig.system) }
        }
        setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
            HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                ProvideOpenOverlays {
                    ProfileCardPager(
                        viewModel = viewModel,
                        onBack = { rig.backs++ },
                        onEdit = { rig.edits++ },
                        onChangeDesign = { rig.designs++ },
                        onNewCard = {},
                        fullWindow = false,
                        onToggleFullWindow = null,
                    )
                }
            }
            }
        }
        waitUntil(timeoutMillis = 10_000) { viewModel.uiState.value.cards.any { it.audience == friends } }
        return rig
    }

    private fun ComposeUiTest.hasText(description: String) =
        onAllNodesWithContentDescription(description, substring = false).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun openingShowsTheOverviewGridAndNoPager() = runDesktopComposeUiTest(width = 824, height = 1784) {
        screen()
        waitUntil(timeoutMillis = 10_000) { hasText("Open Friends card") }
        onAllNodesWithContentDescription("Save card image").assertCountEquals(0)
        onAllNodesWithContentDescription("Edit").assertCountEquals(0)
    }

    @Test
    fun tappingATileOpensThatCardAndBackReturnsToTheOverview() = runDesktopComposeUiTest(width = 824, height = 1784) {
        val rig = screen()
        waitUntil(timeoutMillis = 10_000) { hasText("Open Friends card") }
        onNodeWithContentDescription("Open Friends card").performClick()
        waitUntil(timeoutMillis = 10_000) { hasText("Edit") }
        assertEquals(friends, rig.viewModel.uiState.value.selectedAudience)
        onAllNodesWithContentDescription("Open Friends card").assertCountEquals(0)

        runOnIdle { rig.system.backCompleted() }
        waitUntil(timeoutMillis = 10_000) { hasText("Open Friends card") }
        assertEquals(0, rig.backs)
    }

    @Test
    fun comingBackFromTheEditorSkipsTheOverview() = runDesktopComposeUiTest(width = 824, height = 1784) {
        screen(returningFromEditor = true)
        waitUntil(timeoutMillis = 10_000) { hasText("Edit") }
        onAllNodesWithContentDescription("Open Friends card").assertCountEquals(0)
    }

    @Test
    fun theOpenedCardHasAShareOnlyToolbarAndAPencilMenuForEditAndEditDesign() = runDesktopComposeUiTest(width = 824, height = 1784) {
        val rig = screen(returningFromEditor = true)
        waitUntil(timeoutMillis = 10_000) { hasText("Edit") }
        // JVM has no NFC, so the toolbar is the one save-the-image action.
        onAllNodesWithContentDescription("Save card image").assertCountEquals(1)
        onAllNodesWithContentDescription("Edit design").assertCountEquals(0)
        onAllNodesWithContentDescription("Edit").assertCountEquals(1)

        onNodeWithContentDescription("Edit").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Edit design").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Edit design").performClick()
        waitForIdle()
        assertEquals(1, rig.designs)
        assertEquals(0, rig.edits)
        assertTrue(rig.viewModel.uiState.value.cards.isNotEmpty())
    }

    @Test
    fun thePencilMenusEditItemOpensTheContentEditor() = runDesktopComposeUiTest(width = 824, height = 1784) {
        val rig = screen(returningFromEditor = true)
        waitUntil(timeoutMillis = 10_000) { hasText("Edit") }
        onNodeWithContentDescription("Edit").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Edit design").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Edit")[0].performClick()
        waitForIdle()
        assertEquals(1, rig.edits)
        assertEquals(0, rig.designs)
    }
}
