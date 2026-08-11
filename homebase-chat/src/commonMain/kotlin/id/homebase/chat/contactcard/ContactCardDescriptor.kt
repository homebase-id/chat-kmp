package id.homebase.chat.contactcard

import id.homebase.api.util.codePointCount
import kotlinx.serialization.Serializable

/**
 * Wire format for a shared contact card. Rides in the chat message header's
 * `appData.content` alongside `appData.dataType = ChatProtocol.ChatContactCardMessageDataType`,
 * so it renders with the message index — no payload fetch on scroll.
 *
 * [phones] are E.164 where the source allowed it; a legacy value the importer could not
 * normalize is carried verbatim so the card still shows the number the sender saw.
 */
@Serializable
data class ContactCardDescriptor(
    val displayName: String,
    val givenName: String = "",
    val surname: String = "",
    val organization: String = "",
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val schemaVersion: Int = 1,
) {
    fun summaryLine(): String = displayName.ifBlank {
        organization.ifBlank { phones.firstOrNull() ?: emails.firstOrNull() ?: FALLBACK_SUMMARY }
    }

    fun isValid(): Boolean {
        if (displayName.codePointCount() > MAX_NAME_CODEPOINTS) return false
        if (givenName.codePointCount() > MAX_NAME_CODEPOINTS) return false
        if (surname.codePointCount() > MAX_NAME_CODEPOINTS) return false
        if (organization.codePointCount() > MAX_NAME_CODEPOINTS) return false
        if (phones.size > MAX_VALUES_PER_KIND || emails.size > MAX_VALUES_PER_KIND) return false
        if (phones.any { it.codePointCount() > MAX_VALUE_CODEPOINTS }) return false
        if (emails.any { it.codePointCount() > MAX_VALUE_CODEPOINTS }) return false
        return displayName.isNotBlank() || phones.isNotEmpty() || emails.isNotEmpty()
    }

    companion object {
        const val MAX_NAME_CODEPOINTS = 80
        const val MAX_VALUE_CODEPOINTS = 120
        const val MAX_VALUES_PER_KIND = 10
        const val FALLBACK_SUMMARY = "Contact"
    }
}
