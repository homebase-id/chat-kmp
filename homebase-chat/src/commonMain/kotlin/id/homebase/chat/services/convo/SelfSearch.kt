package id.homebase.chat.services.convo

import id.homebase.api.client.auth.OwnerSession

/** True when [query] hits the signed-in user's own display name or handle (case-insensitive). */
fun OwnerSession?.matchesSelfQuery(query: String): Boolean {
    if (this == null || query.isBlank()) return false
    return displayName?.contains(query, ignoreCase = true) == true ||
        odinId.toString().contains(query, ignoreCase = true)
}

/** Display label for the signed-in user: their name, or their handle when the name isn't loaded. */
fun OwnerSession.selfDisplayLabel(): String =
    displayName?.ifBlank { null } ?: odinId.domainName

/**
 * Share-picker search predicate: a conversation matches on its display name, or — for the
 * self conversation, whose display name is the literal "You" label — when the query hits the
 * owner's own name/handle, so searching your own name surfaces Note to Self (#984).
 */
fun EnrichedConversationUiModel.matchesShareQuery(query: String, self: OwnerSession?): Boolean =
    getDisplayName().contains(query, ignoreCase = true) ||
        (conversation.isWithSelf && self.matchesSelfQuery(query))
