package id.homebase.core.ui.screens.card

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import id.homebase.api.client.profile.ProfileAttributeTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun pickingADesignSavesOnlyOnSaveAndBackLeavesNothingUnsaved() {
        val fixtures = ProfileCardViewModelTest()
        val wire = CardWireHarness()
        val repository: ProfileRepository = runBlocking { wire.profileRepository() }
        val friends = CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends")

        runDesktopComposeUiTest(width = 824, height = 1784) {
            val source = ProfileCardViewModelTest.FakeSource(
                fixtures.profile.filterNot { it.type == ProfileAttributeTypes.PHOTO },
                cardRepository = CardRepository(ProfileRepositoryCardStore(repository)),
            ).apply {
                liveCards = { repository.loadAttributes() }
                circleList = listOf(CardCircle(FRIENDS_CIRCLE_ID, "Friends"))
            }
            val viewModel = ProfileCardViewModel(source) { error("the JVM WebView slot is not part of this flow") }
            var mode by mutableStateOf(EditorMode.Design)
            setContent {
                HomebaseTheme(darkTheme = false, updatesSystemChrome = false) {
                    val editorState by viewModel.uiState.collectAsStateWithLifecycle()
                    ProfileCardEditorContent(
                        uiState = editorState,
                        mode = mode,
                        step = EditorStep.Design,
                        onStep = {},
                        onBack = { mode = EditorMode.Content },
                        onSelect = viewModel::onDesignSelected,
                        onOption = viewModel::onOptionSelected,
                        onBlockOrder = viewModel::onBlockOrderChanged,
                        onSave = viewModel::onSaveDesign,
                        snackbarHostState = remember { SnackbarHostState() },
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
            waitUntil(timeoutMillis = 10_000) { viewModel.uiState.value.cards.any { it.audience == friends } }
            viewModel.onCardOpened(friends)
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Choose a design").fetchSemanticsNodes().isNotEmpty() }

            onNodeWithContentDescription("Poster").performClick()
            waitForIdle()
            assertTrue(wire.putBodies.none { it.jsonObject["data"]?.jsonObject?.get("design") == JsonPrimitive(CardDesign.POSTER) })
            onNodeWithContentDescription("Save").performClick()
            waitUntil(timeoutMillis = 10_000) {
                wire.putBodies.any { body ->
                    body.jsonObject["type"]?.jsonPrimitive?.content == ProfileAttributeTypes.PROFILE_CARD &&
                        body.jsonObject["data"]?.jsonObject?.get("design") == JsonPrimitive(CardDesign.POSTER)
                }
            }
            waitUntil(timeoutMillis = 10_000) { !viewModel.uiState.value.isSavingDesign }
            assertEquals(CardDesign.POSTER, viewModel.uiState.value.selectedCard?.design)

            onNodeWithContentDescription("Back").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Shown to Friends").fetchSemanticsNodes().isNotEmpty() }
            viewModel.onPreviewDiscarded()
            assertEquals(null, viewModel.uiState.value.previewDesign)
            assertFalse(viewModel.uiState.value.hasUnsavedChanges)
            assertEquals(CardDesign.POSTER, viewModel.uiState.value.design)
        }
    }
}
