package id.homebase.chat.services

import kotlin.uuid.Uuid

actual class ShareSuggestionDonor actual constructor() {
    actual fun donateAfterSend(
        conversationId: Uuid,
        conversationName: String,
        isGroup: Boolean,
        participantNames: List<String>,
    ) {
        // No-op on Android — share suggestions are handled via ShortcutManager in the app module.
    }
}
