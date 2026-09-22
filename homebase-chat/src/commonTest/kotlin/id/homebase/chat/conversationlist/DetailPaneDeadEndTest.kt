package id.homebase.chat.conversationlist

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A compact window owned by an empty detail pane has no list, no back button and no bottom
 * navigation — force-quit to recover. These assert no input produces it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3AdaptiveApi::class)
class DetailPaneDeadEndTest {

    private val convA = Uuid.parse("cc0577d2-2c92-4843-b08d-166e05ad4c19")
    private val convB = Uuid.parse("37c7d258-e56c-4446-bf6c-0fcc12862577")

    private fun conversation(id: Uuid) = EnrichedConversationUiModel(
        conversation = ConversationUiModel(
            id = id,
            name = "convo",
            lastMessage = "",
            latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
            avatarInitials = "",
            avatarTiny = null,
            lastRead = Instant.fromEpochMilliseconds(0),
            avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
            admins = emptySet(),
            participants = listOf(OdinId("frodo.demo.rocks")),
        ),
        participants = emptyList(),
        missingConnections = emptyList(),
    )

    private val loaded = listOf(conversation(convA), conversation(convB))

    private fun directive(partitions: Int) =
        PaneScaffoldDirective.Default.copy(maxHorizontalPartitions = partitions)

    @Test
    fun a_selection_the_list_has_not_produced_yet_is_not_an_open_conversation() {
        assertEquals(ChatDetail.None, chatDetail(null, loaded))
        assertEquals(ChatDetail.Loading(convA), chatDetail(convA, emptyList()))
        assertEquals(ChatDetail.Open(loaded[0]), chatDetail(convA, loaded))
    }

    @Test
    fun the_six_layouts() {
        val shown = PaneAdaptedValue.Expanded
        val hidden = PaneAdaptedValue.Hidden
        val open = ChatDetail.Open(loaded[0])
        // The whole input domain: only a compact open conversation hides the list.
        val table = listOf(
            Triple(false, ChatDetail.None, shown to hidden),
            Triple(false, ChatDetail.Loading(convA), shown to hidden),
            Triple(false, open, hidden to shown),
            Triple(true, ChatDetail.None, shown to shown),
            Triple(true, ChatDetail.Loading(convA), shown to shown),
            Triple(true, open, shown to shown),
        )
        for ((isExpanded, detail, expected) in table) {
            val value = chatScaffoldValue(isExpanded, detail)
            assertEquals(
                expected,
                value[ListDetailPaneScaffoldRole.List] to
                    value[ListDetailPaneScaffoldRole.Detail],
                "isExpanded=$isExpanded detail=$detail",
            )
        }
    }

    @Composable
    private fun Host(isExpanded: Boolean, detail: ChatDetail) {
        ListDetailPaneScaffold(
            directive = directive(if (isExpanded) 2 else 1),
            value = chatScaffoldValue(isExpanded, detail),
            listPane = { AnimatedPane { Text("list") } },
            detailPane = {
                AnimatedPane {
                    Text(
                        if (detail is ChatDetail.Open) {
                            "detail=${detail.conversation.conversation.id}"
                        } else {
                            "detail=none"
                        }
                    )
                }
            },
        )
    }

    // A→B yields an equal ThreePaneScaffoldValue, so only a render shows the swap.

    @Test
    fun compact_warm_swap_then_back_to_list() = runComposeUiTest {
        var detail by mutableStateOf<ChatDetail>(ChatDetail.None)
        setContent { MaterialTheme { Host(isExpanded = false, detail = detail) } }
        waitForIdle()
        onNodeWithText("list").assertExists()

        detail = ChatDetail.Open(conversation(convA))
        waitForIdle()
        onNodeWithText("detail=$convA").assertExists()

        // Warm notification tap for B while the user is on A.
        detail = ChatDetail.Open(conversation(convB))
        waitForIdle()
        onNodeWithText("detail=$convB").assertExists()

        detail = ChatDetail.None
        waitForIdle()
        onNodeWithText("list").assertExists()
    }

    @Test
    fun expanded_keeps_the_list_while_swapping_the_detail() = runComposeUiTest {
        var detail by mutableStateOf<ChatDetail>(ChatDetail.Open(conversation(convA)))
        setContent { MaterialTheme { Host(isExpanded = true, detail = detail) } }
        waitForIdle()
        onNodeWithText("list").assertExists()
        onNodeWithText("detail=$convA").assertExists()

        detail = ChatDetail.Open(conversation(convB))
        waitForIdle()
        onNodeWithText("list").assertExists()
        onNodeWithText("detail=$convB").assertExists()
    }
}
