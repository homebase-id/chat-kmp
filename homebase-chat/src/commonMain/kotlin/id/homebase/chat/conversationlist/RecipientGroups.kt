package id.homebase.chat.conversationlist

import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.convo.EnrichedConversationUiModel

internal fun recipientGroups(conversations: List<EnrichedConversationUiModel>, contacts: List<ContactUiModel>): List<RecipientGroupModel> {
    val self = conversations.firstOrNull { it.conversation.isWithSelf }
    val others = conversations.filter { !it.conversation.isWithSelf }
    val recents = others.filter { !it.conversation.isGroupConversation }.take(5)
    val groups = others.filter { it.conversation.isGroupConversation }.sortedBy { it.getDisplayName().lowercase() }
    return buildList {
        if (self != null) add(RecipientGroupModel(RecipientType.You, listOf(RecipientModel.Conversation(self))))
        if (recents.isNotEmpty()) add(RecipientGroupModel(RecipientType.Recents, recents.map { RecipientModel.Conversation(it) }))
        if (contacts.isNotEmpty()) add(RecipientGroupModel(RecipientType.Contacts, contacts.sortedBy { it.name.lowercase() }.map { RecipientModel.Contact(it) }))
        if (groups.isNotEmpty()) add(RecipientGroupModel(RecipientType.Groups, groups.map { RecipientModel.Conversation(it) }))
    }
}

/** The recipients whose name, identity or chat title holds [query]; groups left empty go. */
fun List<RecipientGroupModel>.matching(query: String, youLabel: String): List<RecipientGroupModel> {
    if (query.isEmpty()) return this
    return mapNotNull { group ->
        val hits = group.recipients.filter { recipient ->
            when (recipient) {
                is RecipientModel.Contact ->
                    recipient.contact.name.contains(query, ignoreCase = true) ||
                        recipient.contact.odinId.domainName.contains(query, ignoreCase = true)
                is RecipientModel.Conversation -> recipient.conversation.getDisplayName(youLabel = youLabel).contains(query, ignoreCase = true)
            }
        }
        if (hits.isEmpty()) null else group.copy(recipients = hits)
    }
}
