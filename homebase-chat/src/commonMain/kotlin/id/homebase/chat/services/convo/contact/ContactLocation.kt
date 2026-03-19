package id.homebase.chat.services.convo.contact

import kotlinx.serialization.Serializable

@Serializable
data class ContactLocation(
    val city: String?,
    val country: String?
)