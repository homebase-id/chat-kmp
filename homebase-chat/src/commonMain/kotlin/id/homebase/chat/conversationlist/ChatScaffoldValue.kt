@file:OptIn(ExperimentalMaterial3AdaptiveApi::class)

package id.homebase.chat.conversationlist

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import kotlin.uuid.Uuid

internal sealed interface ChatDetail {
    data class Open(val conversation: EnrichedConversationUiModel) : ChatDetail

    /** Selected, but the list hasn't produced it yet — a notification tap outruns the stream. */
    data class Loading(val id: Uuid) : ChatDetail

    data object None : ChatDetail
}

internal fun chatDetail(
    selectedConversationId: Uuid?,
    activeConversations: List<EnrichedConversationUiModel>,
): ChatDetail {
    if (selectedConversationId == null) return ChatDetail.None
    val open = activeConversations.find { it.conversation.id == selectedConversationId }
    return if (open == null) ChatDetail.Loading(selectedConversationId) else ChatDetail.Open(open)
}

private val adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies()

internal fun chatScaffoldValue(isExpanded: Boolean, detail: ChatDetail): ThreePaneScaffoldValue =
    calculateThreePaneScaffoldValue(
        maxHorizontalPartitions = if (isExpanded) 2 else 1,
        adaptStrategies = adaptStrategies,
        // One destination is enough for a two-pane window: the library expands the destination's
        // own pane, then walks Primary/Secondary/Tertiary to fill whatever partition is left over.
        currentDestination = ThreePaneScaffoldDestinationItem<Nothing>(
            pane = if (detail is ChatDetail.Open) {
                ListDetailPaneScaffoldRole.Detail
            } else {
                ListDetailPaneScaffoldRole.List
            },
        ),
    )
