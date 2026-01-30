package id.homebase.chat.services

import kotlin.uuid.Uuid

data class SendMessageResult(val fileId: Uuid, val uniqueId: Uuid, val versionTag: Uuid)