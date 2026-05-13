package id.homebase.chat.services

import kotlin.uuid.Uuid

// Browsers don't have a share-suggestions surface analogous to Siri/Android
// shortcuts, so this is a no-op like the Android/Desktop actuals.
actual class ShareSuggestionDonor actual constructor() {
    actual fun donateAfterSend(
        conversationId: Uuid,
        conversationName: String,
        isGroup: Boolean,
        participantNames: List<String>,
    ) {
        // No-op on web.
    }
}
