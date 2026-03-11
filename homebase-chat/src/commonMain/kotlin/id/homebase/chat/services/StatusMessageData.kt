package id.homebase.chat.services

import id.homebase.api.common.OdinId

class StatusMessageData(
    val statusMessage: StatusMessage,
    val subject: OdinId? = null
)