package id.homebase.chat.services

data class MessageBuildResult(
    val headerContent: String,
    val payloadBundle: PayloadBundle?
)