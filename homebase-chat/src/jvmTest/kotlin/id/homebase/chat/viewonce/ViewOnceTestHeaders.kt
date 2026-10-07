package id.homebase.chat.viewonce

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.uuid.Uuid

internal const val VO_OWNER = "owner.test"
internal const val VO_SENDER = "alice.test"
internal const val DAY_MS = 24 * 60 * 60 * 1000L

internal suspend fun ownerCredentials(): CredentialsManager = CredentialsManager().also {
    it.setActiveCredentials(
        ApiCredentials.create(
            domain = OdinId(VO_OWNER),
            clientAccessToken = "test-token",
            sharedSecret = SecureByteArray(ByteArray(16)),
        )
    )
}

internal const val VO_PAYLOAD_JSON =
    """[{"key":"chat_web0","contentType":"image/jpeg","iv":"AAAAAAAAAAAAAAAAAAAAAA==","bytesWritten":10,"lastModified":1}]"""

internal const val VO_DESCRIPTOR = """{\"schemaVersion\":1,\"kind\":\"image\"}"""

/** A 216 header as the drive serves it, built from JSON so the real deserializer and mapper run. */
internal fun viewOnceHeader(
    fileId: Uuid = Uuid.random(),
    uniqueId: Uuid? = Uuid.random(),
    conversationId: Uuid = Uuid.random(),
    fileState: String = "active",
    author: String = VO_SENDER,
    createdMs: Long,
    updatedMs: Long = createdMs,
    dataType: Int = ChatProtocol.ChatViewOnceMessageDataType,
    content: String = VO_DESCRIPTOR,
    payloadsJson: String = VO_PAYLOAD_JSON,
    previewThumbnailJson: String = "null",
    localReactions: List<String> = emptyList(),
    reactionPreviewJson: String = "null",
    archivalStatus: Int = 0,
): HomebaseFile {
    val localAppData = if (localReactions.isEmpty()) "null" else
        """{"localReactions":[${localReactions.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" }}]}"""
    val uniqueIdJson = uniqueId?.let { "\"$it\"" } ?: "null"
    val json = """{
        "fileId": "$fileId",
        "driveId": "9ff813af-f2d6-1e2f-9b9d-b189e72d1a11",
        "fileState": "$fileState",
        "fileSystemType": "standard",
        "serverFileIsEncrypted": false,
        "keyHeader": {
            "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
            "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
        },
        "fileMetadata": {
            "globalTransitId": "${Uuid.random()}",
            "created": $createdMs,
            "updated": $updatedMs,
            "transitCreated": $createdMs,
            "transitUpdated": 0,
            "isEncrypted": false,
            "senderOdinId": "$author",
            "originalAuthor": "$author",
            "appData": {
                "uniqueId": $uniqueIdJson,
                "tags": null,
                "fileType": ${ChatProtocol.MessageFileType},
                "dataType": $dataType,
                "groupId": "$conversationId",
                "userDate": $createdMs,
                "content": "$content",
                "previewThumbnail": $previewThumbnailJson,
                "archivalStatus": $archivalStatus
            },
            "localAppData": $localAppData,
            "referencedFile": null,
            "reactionPreview": $reactionPreviewJson,
            "versionTag": "${Uuid.random()}",
            "payloads": $payloadsJson,
            "dataSource": null
        },
        "serverMetadata": {
            "accessControlList": {"requiredSecurityGroup": "owner", "circleIdList": null, "odinIdList": null},
            "doNotIndex": false,
            "allowDistribution": true,
            "fileSystemType": "standard",
            "fileByteCount": 100,
            "originalRecipientCount": 0,
            "transferHistory": null
        },
        "priority": 300,
        "fileByteCount": 100
    }"""
    return OdinSystemSerializer.deserialize<HomebaseFile>(json)
}

/** Exactly what the server keeps after WriteDeletedFileHeader: no payloads, content, uniqueId or preview. */
internal fun serverTombstone(
    fileId: Uuid = Uuid.random(),
    createdMs: Long,
    updatedMs: Long,
    dataType: Int = ChatProtocol.ChatViewOnceMessageDataType,
    author: String = VO_SENDER,
): HomebaseFile = viewOnceHeader(
    fileId = fileId,
    uniqueId = null,
    fileState = "deleted",
    author = author,
    createdMs = createdMs,
    updatedMs = updatedMs,
    dataType = dataType,
    content = "",
    payloadsJson = "null",
)

internal fun viewOnceWord(res: StringResource): String = runBlocking { getString(res) }
