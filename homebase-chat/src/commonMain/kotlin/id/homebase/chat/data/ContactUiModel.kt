package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import kotlin.uuid.Uuid
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ContactConnectionState

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
)
