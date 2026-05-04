package id.homebase.core.ui.screens.defragmenter.service

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.sync.database.DriveMainIndexWrapper.PagedScanRow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DefragRowClassifierTest {

    private val driveId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val convoId = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val healthyConvos = setOf(convoId)

    @Test
    fun classify_messageWithCorruptContent_returnsCorruptMessageContent() = runTest {
        val row = chatMessageRow(
            content = "?garbage{",
            groupId = convoId,
        )
        val probe: suspend (HomebaseFile) -> Throwable? = {
            // The probe represents the runtime decoder failing — the
            // classifier just trusts the throwable, so any exception works.
            IllegalStateException("expected '{' but got '?'")
        }

        val state = classifyRow(
            driveId = driveId,
            row = row,
            mapToBasicProbe = null,
            healthyConversationIds = healthyConvos,
            decodeMessageContentProbe = probe,
        )

        val corrupt = state as? CellState.CorruptMessageContent
        assertNotNull(corrupt, "expected CorruptMessageContent, got $state")
        assertEquals("expected '{' but got '?'", corrupt.decodeError)
        assertTrue(
            corrupt.rawContentPrefix.startsWith("?garbage"),
            "rawContentPrefix should start with the bad bytes, got '${corrupt.rawContentPrefix}'",
        )
        assertEquals(convoId, corrupt.conversationId)
    }

    @Test
    fun classify_orphanMessageWithCorruptContent_orphanWinsOverCorrupt() = runTest {
        val orphanGroupId = Uuid.parse("00000000-0000-0000-0000-00000000beef")
        val row = chatMessageRow(
            content = "?garbage{",
            groupId = orphanGroupId,
        )
        val probe: suspend (HomebaseFile) -> Throwable? = { IllegalStateException("decode boom") }

        val state = classifyRow(
            driveId = driveId,
            row = row,
            mapToBasicProbe = null,
            healthyConversationIds = healthyConvos,
            decodeMessageContentProbe = probe,
        )

        // OrphanChatMessage is checked before the content-decode probe so the
        // parent recovery path gets a chance before the user is asked to
        // delete a row that may resolve on its own.
        assertTrue(
            state is CellState.OrphanChatMessage,
            "expected orphan-wins-over-corrupt, got $state",
        )
    }

    @Test
    fun classify_messageWithHealthyContent_returnsHealthy() = runTest {
        val row = chatMessageRow(
            content = """{"message":"hi","deliveryStatus":50}""",
            groupId = convoId,
        )
        val probe: suspend (HomebaseFile) -> Throwable? = { null }

        val state = classifyRow(
            driveId = driveId,
            row = row,
            mapToBasicProbe = null,
            healthyConversationIds = healthyConvos,
            decodeMessageContentProbe = probe,
        )

        assertEquals(CellState.Healthy, state)
    }

    @Test
    fun classify_messageWithProbeNull_returnsHealthy() = runTest {
        val row = chatMessageRow(
            content = "?garbage{",
            groupId = convoId,
        )

        val state = classifyRow(
            driveId = driveId,
            row = row,
            mapToBasicProbe = null,
            healthyConversationIds = healthyConvos,
            decodeMessageContentProbe = null,  // probe-disabled mode
        )

        // Without a probe, content corruption can't be detected — the row
        // falls through to Healthy. This is the same behaviour as before
        // the corrupt-content branch was added.
        assertEquals(CellState.Healthy, state)
    }

    private fun chatMessageRow(
        content: String,
        groupId: Uuid,
        fileId: Uuid = Uuid.random(),
        rowId: Long = 1L,
    ): PagedScanRow {
        val jsonContent = content.replace("\\", "\\\\").replace("\"", "\\\"")
        val jsonHeader = """{
            "driveId": "$driveId",
            "fileId": "$fileId",
            "fileState": "active",
            "fileSystemType": "standard",
            "serverFileIsEncrypted": true,
            "keyHeader": {
                "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
            },
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": 1000,
                "updated": 1000,
                "transitCreated": 0,
                "transitUpdated": 0,
                "serverFileIsEncrypted": true,
                "senderOdinId": "test.sender",
                "originalAuthor": "test.sender",
                "appData": {
                    "uniqueId": "${Uuid.random()}",
                    "tags": null,
                    "fileType": 7878,
                    "dataType": 0,
                    "groupId": "$groupId",
                    "userDate": 1000,
                    "content": "$jsonContent",
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
                "allowDistribution": false,
                "fileSystemType": "standard",
                "fileByteCount": 1000,
                "originalRecipientCount": 0,
                "transferHistory": null
            },
            "priority": 300,
            "fileByteCount": 1000
        }"""
        return PagedScanRow(
            rowId = rowId,
            fileId = fileId,
            userDate = 1000L,
            archivalStatus = 0L,
            jsonHeader = jsonHeader,
        )
    }
}
