package id.homebase.chat.services.convo

import kotlinx.serialization.Serializable
import id.homebase.api.common.OdinId

@Serializable
data class ConversationAppDataJson(
    val title: String? = "",
    val version: Int = 0,
    val recipients: List<OdinId> = listOf(),

    // this may come back as null
    val admins: List<OdinId>? = null
)


@Serializable
data class ConversationAdminContentJson(
    // this may come back as null
    val admins: List<OdinId>? = null
)
