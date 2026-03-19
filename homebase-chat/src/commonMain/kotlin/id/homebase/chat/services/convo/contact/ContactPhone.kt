package id.homebase.chat.services.convo.contact

import kotlinx.serialization.Serializable

@Serializable
data class ContactPhone(
    val number: String?
)