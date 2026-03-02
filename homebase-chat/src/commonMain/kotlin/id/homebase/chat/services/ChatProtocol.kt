package id.homebase.chat.services

import kotlin.uuid.Uuid

object ChatProtocol {

    val ChatAppId = Uuid.parse("2d781401-3804-4b57-b4aa-d8e4e2ef39f4")

    const val ContactFileType = 100

    val ConversationWithYourselfId: Uuid = Uuid.parse("e4ef2382-ab3c-405d-a8b5-ad3e09e980dd")
    const val ConversationPayloadKey = "convo_pk" // TODO: Explain what this represents
    const val ConversationImageKey = "convo_img"

    const val CHAT_CONVERSATION_LOCAL_METADATA_FILE_TYPE = 8889;

    const val ConversationFileType = 8888
    const val MessageFileType = 7878

    const val ChatArchiveStatusDeleted = 2

    const val DEFAULT_PAYLOAD_DESCRIPTOR_KEY = "pld_desc"

    const val PAYLOAD_KEY_MESSAGE_WEB = "chat_web"
    const val PAYLOAD_KEY_LINKS = "chat_links"

    const val DEFAULT_PAYLOAD_KEY = "dflt_key"
    const val MAX_PAYLOAD_DESCRIPTOR_BYTES = 1024
    const val MAX_HEADER_CONTENT_BYTES = 7000
}
