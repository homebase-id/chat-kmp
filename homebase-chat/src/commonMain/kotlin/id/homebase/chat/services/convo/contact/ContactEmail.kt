package id.homebase.chat.services.convo.contact

import kotlinx.serialization.Serializable

@Serializable
data class ContactEmail(
    val email: String?
)