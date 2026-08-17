package id.homebase.chat.contactcard

import id.homebase.api.common.OdinId
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
    /**
     * The Homebase identity this card is *about* (not its author — that's the envelope's
     * `originalAuthor`). Optional: a vCard-sourced card has none. Its only use today is the
     * published avatar at `https://<odinId>/pub/image`, which is why the card can show a real
     * picture without carrying one.
     */
    val odinId: String = "",
    val schemaVersion: Int = 1,
) {
    /** Blank when a card carries nothing renderable; the caller supplies a localized fallback. */
    fun summaryLine(): String = displayName.ifBlank {
        organization.ifBlank { (phones + emails).firstOrNull { it.isNotBlank() }.orEmpty() }
    }

    /**
     * The identity, only when it really parses as one. A card authored elsewhere can put anything
     * in the field, and it becomes a URL host — so it is validated at the point of use, not merely
     * on arrival.
     */
    fun identity(): OdinId? =
        odinId.trim().ifBlank { null }?.let { runCatching { OdinId(it) }.getOrNull() }

    fun isValid(): Boolean {
        if (displayName.codePointCount() > MAX_NAME_CODEPOINTS) return false
        if (odinId.codePointCount() > MAX_VALUE_CODEPOINTS) return false
        if (givenName.codePointCount() > MAX_NAME_CODEPOINTS) return false
        if (surname.codePointCount() > MAX_NAME_CODEPOINTS) return false
        if (organization.codePointCount() > MAX_NAME_CODEPOINTS) return false
        if (phones.size > MAX_VALUES_PER_KIND || emails.size > MAX_VALUES_PER_KIND) return false
        if (phones.any { it.codePointCount() > MAX_VALUE_CODEPOINTS }) return false
        if (emails.any { it.codePointCount() > MAX_VALUE_CODEPOINTS }) return false
        return displayName.isNotBlank() || phones.isNotEmpty() || emails.isNotEmpty() ||
            identity() != null
    }

    companion object {
        const val MAX_NAME_CODEPOINTS = 80
        const val MAX_VALUE_CODEPOINTS = 120
        const val MAX_VALUES_PER_KIND = 10
    }
}
