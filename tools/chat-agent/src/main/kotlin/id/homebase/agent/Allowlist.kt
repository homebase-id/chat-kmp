package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import kotlin.uuid.Uuid

class Allowlist(val conversationIds: Set<Uuid>, val authors: Set<OdinId>) {
    fun allowsConversation(id: Uuid): Boolean = id in conversationIds

    fun allowsAuthor(author: OdinId?): Boolean = author != null && author in authors

    fun requireConversation(id: Uuid) =
        require(allowsConversation(id)) { "conversation $id is not on the allowlist" }

    companion object {
        fun default(owner: OdinId) = Allowlist(setOf(ChatProtocol.ConversationWithYourselfId), setOf(owner))
    }
}
