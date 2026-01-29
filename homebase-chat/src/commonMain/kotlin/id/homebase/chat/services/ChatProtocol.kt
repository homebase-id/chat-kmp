package id.homebase.chat.services

import kotlin.uuid.Uuid

object ChatProtocol {

    val CHAT_APP_ID = Uuid.parse("2d781401-3804-4b57-b4aa-d8e4e2ef39f4")

    const val ConversationWithYourselfId = "e4ef2382-ab3c-405d-a8b5-ad3e09e980dd"
    const val CONVERSATION_PAYLOAD_KEY = "convo_pk" // TODO: Explain what this represents
    const val CONVERSATION_IMAGE_KEY = "convo_img"// TODO: Explain what this represents (and where's the tiny)

    const val CONVERSATION_FILE_TYPE = 8888
    const val MESSAGE_FILE_TYPE = 7878

    const val ARCHIVAL_STATUS_DELETED = 2

    const val PAYLOAD_KEY_MESSAGE_WEB = "chat_web"
    const val PAYLOAD_KEY_LINKS = "chat_links"
}
