package id.homebase.chat.services.convo.contact

import kotlinx.serialization.Serializable

@Serializable
data class ContactBirthday(
    val date: String?
)