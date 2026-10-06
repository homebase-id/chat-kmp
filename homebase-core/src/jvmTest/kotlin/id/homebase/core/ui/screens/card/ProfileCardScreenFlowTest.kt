package id.homebase.core.ui.screens.card

import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.common.OdinId
import id.homebase.core.config.CONTACTS_APP_ID
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.RedactedCircleDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class ProfileCardScreenFlowTest {

    private fun circleDef(name: String, appId: String?, id: String, disabled: Boolean = false) = CircleWithMembers(
        RedactedCircleDefinition(id = id, name = name, disabled = disabled, appId = appId?.let { Uuid.parse(it) }),
        members = listOf(OdinId("sam.dotyou.cloud")),
    )

    @Test
    fun introToViewerAndBackOverTheRealRepositoryAndContactsCircleFilter() {
        val fixtures = ProfileCardViewModelTest()
        val wire = CardWireHarness()
        val other = "11111111-2222-4333-8444-555555555555"
        listOf(
            fixtures.publicCardAttribute,
            fixtures.circleCardAttribute(FRIENDS_CIRCLE_ID, "Friends", CardDesign.DOSSIER, 20),
        ).forEach { wire.seed(it.id, it.versionTag, it.type, it.visibility.wireValue, it.data, it.priority, it.acl.circleIdList) }
        val repository: ProfileRepository = runBlocking { wire.profileRepository() }
        val circles = contactsCircles(
            listOf(
                circleDef("Work", CONTACTS_APP_ID, WORK_CIRCLE_ID),
                circleDef("Friends", CONTACTS_APP_ID, FRIENDS_CIRCLE_ID),
                circleDef("Family", CONTACTS_APP_ID, FAMILY_CIRCLE_ID),
                circleDef("Book club", null, "book-club"),
                circleDef("Chat", other, "chat"),
                circleDef("Old crowd", CONTACTS_APP_ID, "old", disabled = true),
            ),
        )
        val chipFriends = hasContentDescription("Card for Friends", substring = true)
        val open = { name: String -> "Open the $name card" }

        runDesktopComposeUiTest(width = 824, height = 1784) {
            val source = ProfileCardViewModelTest.FakeSource(
                fixtures.profile,
                cardRepository = CardRepository(ProfileRepositoryCardStore(repository)),
            ).apply {
                liveCards = { repository.loadAttributes() }
                circleList = circles
            }
            val viewModel = ProfileCardViewModel(source) { error("the JVM WebView slot is not part of this flow") }
            setContent {
                HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                    IntroViewerSwitch(viewModel = viewModel, onBack = {}) { onClose ->
                        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                        SheetTopChrome(
                            uiState = uiState,
                            onClose = onClose,
                            bandDrag = Modifier,
                            handleDrag = Modifier,
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithContentDescription(open("Work")).fetchSemanticsNodes().isNotEmpty()
            }

            val order = listOf("Public", "Family", "Friends", "Work")
            val tilesInReadingOrder = order.map { it to onNodeWithContentDescription(open(it)).fetchSemanticsNode().boundsInRoot }
                .sortedWith(compareBy({ it.second.top }, { it.second.left }))
                .map { it.first }
            assertEquals(order, tilesInReadingOrder)
            assertEquals(
                listOf(CardAudience.Public, CardAudience.Circle(FAMILY_CIRCLE_ID, "Family"), CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends"), CardAudience.Circle(WORK_CIRCLE_ID, "Work")),
                viewModel.uiState.value.cards.map { it.audience },
            )
            listOf("Book club", "Chat", "Old crowd").forEach { onAllNodesWithContentDescription(open(it)).assertCountEquals(0) }
            onAllNodes(chipFriends).assertCountEquals(0)
            assertFalse(viewModel.uiState.value.viewing)

            onNodeWithContentDescription(open("Friends")).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(chipFriends).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription(open("Friends")).fetchSemanticsNodes().isEmpty() }
            assertTrue(viewModel.uiState.value.viewing)
            assertEquals(CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends"), viewModel.uiState.value.selectedAudience)
            assertEquals(CardDesign.DOSSIER, viewModel.uiState.value.design)

            onNodeWithContentDescription("Close").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription(open("Friends")).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(chipFriends).assertCountEquals(0)
            assertFalse(viewModel.uiState.value.viewing)
        }
    }
}
