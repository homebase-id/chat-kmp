package id.homebase.chat.services
import id.homebase.upload.PayloadBundle

data class MessageBuildResult(
    val headerContent: String,
    val payloadBundle: PayloadBundle?
)