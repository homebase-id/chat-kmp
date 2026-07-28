@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.services

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Pins the Ambush embargo on [StatusMessage.EmergencyLocateRequested] (#966-follow-up):
 * a RECEIVED request notice with `emergencyLocateEmbargoUntilMs` in the future must be
 * invisible (`mapToMessageData` returns null) so a captor inspecting the victim's phone
 * sees nothing until the deadline; the SENDER's own copy and any past-embargo or
 * no-embargo copy render normally.
 */
class MessageMapperEmbargoTest {

    private val testDomain = "owner.test"
    private val peerDomain = "peer.test"

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

    private fun buildStatusHeader(
        author: String,
        embargoUntilMs: Long?,
    ): HomebaseFile {
        val now = Clock.System.now().epochSeconds
        val status = StatusMessageData(
            statusMessage = StatusMessage.EmergencyLocateRequested,
            subject = OdinId(testDomain),
            emergencyLocateExplanation = "car broke down, need to find them",
            emergencyLocateWindowHours = 24,
            emergencyLocateEmbargoUntilMs = embargoUntilMs,
        )
        val escapedContent = OdinSystemSerializer.serialize(status).replace("\"", "\\\"")

        val jsonHeader = """{
            "fileId": "${Uuid.random()}",
            "driveId": "${Uuid.random()}",
            "fileState": "active",
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
                "senderOdinId": "$author",
                "originalAuthor": "$author",
                "appData": {
                    "uniqueId": "${Uuid.random()}",
                    "tags": null,
                    "fileType": ${ChatProtocol.MessageFileType},
                    "dataType": ${ChatProtocol.ChatStatusMessageDataType},
                    "groupId": "${Uuid.random()}",
                    "userDate": ${now}000,
                    "content": "$escapedContent",
                    "previewThumbnail": null,
                    "archivalStatus": 0
                },
                "localAppData": null,
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
                "originalRecipientCount": 1,
                "transferHistory": null
            },
            "priority": 300,
            "fileByteCount": 100
        }"""
        return OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)
    }

    private fun futureMs() = Clock.System.now().toEpochMilliseconds() + 3_600_000L
    private fun pastMs() = Clock.System.now().toEpochMilliseconds() - 3_600_000L

    @Test
    fun receivedWithActiveEmbargo_isHidden() = runTest {
        val cm = createTestCredentialsManager()
        val header = buildStatusHeader(author = peerDomain, embargoUntilMs = futureMs())
        assertNull(mapToMessageData(header, cm), "embargoed received notice must not render")
    }

    @Test
    fun ownCopyWithActiveEmbargo_stillRenders() = runTest {
        val cm = createTestCredentialsManager()
        val header = buildStatusHeader(author = testDomain, embargoUntilMs = futureMs())
        val result = mapToMessageData(header, cm)
        assertNotNull(result, "the sender's own copy is never embargoed")
        assertTrue(result.isStatusMessage)
    }

    @Test
    fun receivedWithPastEmbargo_renders() = runTest {
        val cm = createTestCredentialsManager()
        val header = buildStatusHeader(author = peerDomain, embargoUntilMs = pastMs())
        assertNotNull(mapToMessageData(header, cm), "expired embargo must render on next decode")
    }

    @Test
    fun receivedWithoutEmbargo_renders() = runTest {
        val cm = createTestCredentialsManager()
        val header = buildStatusHeader(author = peerDomain, embargoUntilMs = null)
        assertNotNull(mapToMessageData(header, cm), "non-ambush notice renders immediately")
    }
}
