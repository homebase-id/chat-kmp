package id.homebase.chat.services

import co.touchlab.kermit.Logger
import platform.Intents.INInteraction
import platform.Intents.INPerson
import platform.Intents.INPersonHandle
import platform.Intents.INPersonHandleTypeUnknown
import platform.Intents.INSendMessageIntent
import platform.Intents.INSpeakableString
import kotlin.uuid.Uuid

actual class ShareSuggestionDonor actual constructor() {

    actual fun donateAfterSend(
        conversationId: Uuid,
        conversationName: String,
        isGroup: Boolean,
        participantNames: List<String>,
    ) {
        try {
            val recipients = participantNames.map { name ->
                INPerson(
                    personHandle = INPersonHandle(
                        value = name,
                        type = INPersonHandleTypeUnknown
                    ),
                    nameComponents = null,
                    displayName = name,
                    image = null,
                    contactIdentifier = null,
                    customIdentifier = name
                )
            }

            val speakableGroupName = if (isGroup) {
                INSpeakableString(spokenPhrase = conversationName)
            } else null

            val intent = INSendMessageIntent(
                recipients = recipients,
                outgoingMessageType = 0, // INOutgoingMessageTypeUnknown
                content = null,
                speakableGroupName = speakableGroupName,
                conversationIdentifier = conversationId.toString(),
                serviceName = "Homebase",
                sender = null,
                attachments = null
            )

            val interaction = INInteraction(intent = intent, response = null)
            interaction.donateInteractionWithCompletion { error ->
                if (error != null) {
                    Logger.w("ShareSuggestionDonor") {
                        "INInteraction donation failed: ${error.localizedDescription}"
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w("ShareSuggestionDonor") {
                "Failed to donate share suggestion: ${e.message}"
            }
        }
    }
}
