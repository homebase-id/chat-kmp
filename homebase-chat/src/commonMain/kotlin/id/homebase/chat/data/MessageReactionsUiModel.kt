package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.common.OdinId
import kotlin.uuid.Uuid

@Immutable
data class MessageReactionsUiModel(
    val messageId: Uuid,
    val senderOdinId: OdinId,
    val reaction: ReactionContent
)