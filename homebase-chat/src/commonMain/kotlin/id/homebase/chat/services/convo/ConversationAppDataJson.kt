package id.homebase.chat.services.convo

import kotlinx.serialization.Serializable

@Serializable
data class ConversationAppDataJson(
    val title: String? = "",
    val version: Int = 0,
    val recipients: List<String> = listOf()
)
