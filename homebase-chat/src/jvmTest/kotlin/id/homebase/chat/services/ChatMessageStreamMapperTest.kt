@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.services

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Covers the `mapToMessageData` path that populates
 * `MessageUiModel.ownReactions` from `fileMetadata.localAppData.localReactions`.
 *
 * Three branches in the mapper construct a `MessageUiModel`
 * (normal / deleted / catch-fallback); these tests exercise the first two.
 */
class ChatMessageStreamMapperTest {

    private val testDomain = "owner.test"

    private suspend fun createTestCredentialsManager(): CredentialsManager {
        val cm = CredentialsManager()
        cm.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId(testDomain),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(16))
            )
        )
        return cm
    }

    private val sampleLocalReactionsJson =
        """["{\"emoji\":\"❤️\"}", "{\"emoji\":\"🚀\"}"]"""

    // Mapper decodes the JSON-encoded wire format into bare emoji strings, so the
    // rest of the UI can compare against reactionPreview entries directly.
    private val expectedOwnReactions = listOf("❤️", "🚀")

    /**
     * Builds a minimal chat-message HomebaseFile JSON.
     *
     * @param localAppDataJson JSON object literal (e.g. `{"localReactions":[...]}`)
     *        or `null` to emit `"localAppData": null`.
     * @param fileState `"active"` or `"deleted"`.
     */
    private fun buildChatMessageHeader(
        localAppDataJson: String?,
        fileState: String = "active"
    ): HomebaseFile {
        val now = Clock.System.now().epochSeconds
        val messageContent =
            """{"message":"hi","deliveryStatus":20,"isEdited":false,"version":1}"""
        val escapedContent = messageContent.replace("\"", "\\\"")
        val localAppDataField = localAppDataJson ?: "null"

        val jsonHeader = """{
            "fileId": "${Uuid.random()}",
            "driveId": "${Uuid.random()}",
            "fileState": "$fileState",
            "fileSystemType": "standard",
            "serverFileIsEncrypted": false,
            "keyHeader": {
                "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
            },
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": ${now}000,
                "updated": ${now}000,
                "transitCreated": ${now}000,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "sender.test",
                "originalAuthor": "sender.test",
                "appData": {
                    "uniqueId": "${Uuid.random()}",
                    "tags": null,
                    "fileType": ${ChatProtocol.MessageFileType},
                    "dataType": 0,
                    "groupId": "${Uuid.random()}",
                    "userDate": ${now}000,
                    "content": "$escapedContent",
                    "previewThumbnail": null,
                    "archivalStatus": 0
                },
                "localAppData": $localAppDataField,
                "referencedFile": null,
                "reactionPreview": null,
                "versionTag": "${Uuid.random()}",
                "payloads": [],
                "dataSource": null
            },
            "serverMetadata": {
                "accessControlList": {
                    "requiredSecurityGroup": "owner",
                    "circleIdList": null,
                    "odinIdList": null
                },
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

        return OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)
    }

    @Test
    fun mapToMessageData_propagatesLocalReactionsIntoOwnReactions() = runTest {
        val cm = createTestCredentialsManager()
        val header = buildChatMessageHeader(
            localAppDataJson = """{"localReactions": $sampleLocalReactionsJson}"""
        )

        val result = mapToMessageData(header, cm)

        assertNotNull(result)
        assertEquals(expectedOwnReactions, result.ownReactions.toList())
    }

    @Test
    fun mapToMessageData_ownReactionsEmpty_whenLocalAppDataIsAbsent() = runTest {
        val cm = createTestCredentialsManager()
        val header = buildChatMessageHeader(localAppDataJson = null)

        val result = mapToMessageData(header, cm)

        assertNotNull(result)
        assertTrue(result.ownReactions.isEmpty())
    }

    @Test
    fun mapToMessageData_ownReactionsEmpty_whenLocalReactionsIsExplicitNull() = runTest {
        val cm = createTestCredentialsManager()
        val header = buildChatMessageHeader(
            localAppDataJson = """{"localReactions": null}"""
        )

        val result = mapToMessageData(header, cm)

        assertNotNull(result)
        assertTrue(result.ownReactions.isEmpty())
    }

    @Test
    fun mapToMessageData_ownReactionsPropagatedOnDeletedBranch() = runTest {
        val cm = createTestCredentialsManager()
        val header = buildChatMessageHeader(
            localAppDataJson = """{"localReactions": $sampleLocalReactionsJson}""",
            fileState = "deleted"
        )

        val result = mapToMessageData(header, cm)

        assertNotNull(result)
        assertTrue(result.isDeleted)
        assertEquals(expectedOwnReactions, result.ownReactions.toList())
    }
}
