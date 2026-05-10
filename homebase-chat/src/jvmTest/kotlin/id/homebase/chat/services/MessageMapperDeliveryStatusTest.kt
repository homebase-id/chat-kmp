@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.services

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pure-function coverage for [getDeliveryStatus]. The 5 branches:
 *  - groupId == ConversationWithYourselfId → Read (chat with yourself never
 *    has a wire delivery story).
 *  - originalRecipientCount == 0 → Read (no one to deliver to).
 *  - transferHistory.summary == null → Sent (no information yet, optimistic).
 *  - summary.totalFailed > 0 → Failed (one failure poisons the whole
 *    message; the bubble shows the error).
 *  - summary.totalReadByRecipient >= count → Read.
 *  - summary.totalDelivered >= count (no fails, not yet read) → Delivered.
 *  - else → Sent.
 */
class MessageMapperDeliveryStatusTest {

    @Test
    fun groupIdIsConversationWithYourself_returnsRead() {
        val header = buildHeader(
            groupId = ChatProtocol.ConversationWithYourselfId,
            originalRecipientCount = 0,
            transferHistoryJson = "null",
        )
        assertEquals(ChatDeliveryStatus.Read, getDeliveryStatus(header))
    }

    @Test
    fun zeroRecipients_returnsRead() {
        // Some 1:1 conversations with self end up here too via originalRecipientCount=0
        // even when groupId differs — the function falls through to this guard.
        val header = buildHeader(
            groupId = Uuid.random(),
            originalRecipientCount = 0,
            transferHistoryJson = "null",
        )
        assertEquals(ChatDeliveryStatus.Read, getDeliveryStatus(header))
    }

    @Test
    fun missingTransferHistorySummary_returnsSent() {
        val header = buildHeader(
            groupId = Uuid.random(),
            originalRecipientCount = 2,
            transferHistoryJson = "null",
        )
        assertEquals(ChatDeliveryStatus.Sent, getDeliveryStatus(header))
    }

    @Test
    fun anyFailure_returnsFailed() {
        // totalFailed > 0 wins over Read/Delivered — even if everyone else read it.
        val header = buildHeader(
            groupId = Uuid.random(),
            originalRecipientCount = 3,
            transferHistoryJson = summaryJson(failed = 1, delivered = 2, read = 2),
        )
        assertEquals(ChatDeliveryStatus.Failed, getDeliveryStatus(header))
    }

    @Test
    fun allRead_returnsRead() {
        val header = buildHeader(
            groupId = Uuid.random(),
            originalRecipientCount = 3,
            transferHistoryJson = summaryJson(failed = 0, delivered = 3, read = 3),
        )
        assertEquals(ChatDeliveryStatus.Read, getDeliveryStatus(header))
    }

    @Test
    fun allDeliveredNotAllRead_returnsDelivered() {
        val header = buildHeader(
            groupId = Uuid.random(),
            originalRecipientCount = 3,
            transferHistoryJson = summaryJson(failed = 0, delivered = 3, read = 1),
        )
        assertEquals(ChatDeliveryStatus.Delivered, getDeliveryStatus(header))
    }

    @Test
    fun partialDelivery_returnsSent() {
        val header = buildHeader(
            groupId = Uuid.random(),
            originalRecipientCount = 3,
            transferHistoryJson = summaryJson(failed = 0, delivered = 1, read = 0),
        )
        assertEquals(ChatDeliveryStatus.Sent, getDeliveryStatus(header))
    }

    private fun summaryJson(failed: Int, delivered: Int, read: Int): String =
        """{"summary":{"totalInOutbox":0,"totalFailed":$failed,"totalDelivered":$delivered,"totalReadByRecipient":$read}}"""

    private fun buildHeader(
        groupId: Uuid,
        originalRecipientCount: Int,
        transferHistoryJson: String,
    ): HomebaseFile {
        val now = Clock.System.now().epochSeconds
        val json = """{
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
                "senderOdinId": "sender.test",
                "originalAuthor": "sender.test",
                "appData": {
                    "uniqueId": "${Uuid.random()}",
                    "tags": null,
                    "fileType": ${ChatProtocol.MessageFileType},
                    "dataType": 0,
                    "groupId": "$groupId",
                    "userDate": ${now}000,
                    "content": "{}",
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
                "originalRecipientCount": $originalRecipientCount,
                "transferHistory": $transferHistoryJson
            },
            "priority": 300,
            "fileByteCount": 100
        }"""
        return OdinSystemSerializer.deserialize<HomebaseFile>(json)
    }
}
