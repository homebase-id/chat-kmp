package id.homebase.chat.services

import kotlin.uuid.Uuid

/**
 * Donates share suggestions to the OS so recent conversations appear
 * in the share sheet's suggestion row.
 *
 * - iOS: Donates INSendMessageIntent interactions to Siri.
 * - Android: No-op (handled via ShortcutManager in the app module).
 * - Desktop: No-op.
 */
expect class ShareSuggestionDonor() {
    fun donateAfterSend(
        conversationId: Uuid,
        conversationName: String,
        isGroup: Boolean,
        participantNames: List<String>,
    )
}
