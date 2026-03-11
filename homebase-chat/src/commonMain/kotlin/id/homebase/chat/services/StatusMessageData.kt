package id.homebase.chat.services

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

@Serializable
class StatusMessageData(
    val statusMessage: StatusMessage,
    val subject: OdinId? = null
)