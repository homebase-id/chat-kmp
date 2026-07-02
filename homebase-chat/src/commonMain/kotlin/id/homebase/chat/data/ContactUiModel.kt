@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.initials
import id.homebase.api.client.contacts.resolveDisplayName
import id.homebase.api.common.OdinId
import id.homebase.api.common.publicImageUrl
import id.homebase.chat.services.convo.contact.ContactConnectionState
import id.homebase.core.util.initials
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Immutable
data class ContactUiModel(
    val id: Uuid,
    val odinId: OdinId,
    val name: String, //TODO: change to ContactName class?
    val avatarInitials: String,
    val avatarUrl: String = "",
    val status: String = "Available",

    val connection: RedactedIdentityConnectionRegistration? = null,
    val connectionState: ContactConnectionState = ContactConnectionState.Unknown
) {
    companion object {
        /** Identity-only fallback when no saved contact exists — never emits blank avatar fields. */
        fun fallbackFor(odinId: OdinId): ContactUiModel = ContactUiModel(
            id = odinId.toHashId(),
            odinId = odinId,
            name = odinId.domainName,
            avatarInitials = odinId.domainName.initials(),
            avatarUrl = odinId.publicImageUrl(),
            connection = null,
            connectionState = ContactConnectionState.NotConnected,
        )
    }
}

/**
 * Projects the server-shaped [Contact] domain model (from `ContactRepository`) into the
 * connection-oriented chat UI model. Identity-keyed: a contact with no odinId (e.g. a manual
 * phone-only entry) can't be a [ContactUiModel] and is skipped (null). Connection state is layered
 * on later by `ContactService`.
 */
fun Contact.toContactUiModel(): ContactUiModel? {
    val odinIdStr = content.odinId?.takeIf { it.isNotBlank() } ?: return null
    val odin = OdinId(odinIdStr)
    return ContactUiModel(
        id = uniqueId,
        odinId = odin,
        name = content.name.resolveDisplayName(
            odinId = odinIdStr,
            phone = content.phone?.number,
            email = content.email?.email,
        ) ?: odin.domainName,
        avatarInitials = content.name.initials(),
        avatarUrl = odin.publicImageUrl(),
    )
}
