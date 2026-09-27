package id.homebase.chat.services.convo

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.LocalAppMetadata
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class StatusMessageDispatchTest {

    private fun statusFile(
        status: StatusMessage,
        author: String?,
        driveId: Uuid = Uuid.random(),
        fileState: FileState = FileState.Active,
        createdMs: Long = 1_000L,
    ): HomebaseFile = HomebaseFile(
        fileId = Uuid.random(),
        driveId = driveId,
        serverFileIsEncrypted = false,
        fileState = fileState,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader.newRandom16(),
        fileMetadata = FileMetadata(
            appData = AppFileMetaData(
                uniqueId = Uuid.random(),
                tags = null,
                fileType = ChatProtocol.MessageFileType,
                dataType = ChatProtocol.ChatStatusMessageDataType,
                groupId = null,
                userDate = null,
                content = OdinSystemSerializer.serialize(StatusMessageData(statusMessage = status)),
                previewThumbnail = null,
                archivalStatus = null,
            ),
            localAppData = LocalAppMetadata(tags = emptyList()),
            created = UnixTimeUtc(createdMs),
            updated = UnixTimeUtc.ZeroTime,
            isEncrypted = false,
            senderOdinId = author?.let(::OdinId),
            originalAuthor = author?.let(::OdinId),
            versionTag = Uuid.random(),
            payloads = null,
        ),
        serverMetadata = ServerMetadata(
            accessControlList = AccessControlList(requiredSecurityGroup = "Owner"),
            allowDistribution = false,
            fileSystemType = FileSystemType.Standard,
            fileByteCount = 100,
            originalRecipientCount = 0,
            transferHistory = null,
        ),
        priority = 100,
        fileByteCount = 100,
    )

    private class Calls {
        val log = mutableListOf<String>()
        val onDesignated: suspend (OdinId, HomebaseFile) -> Unit = { s, _ -> log += "designated:${s.domainName}" }
        val onRevoked: suspend (OdinId, HomebaseFile) -> Unit = { s, _ -> log += "revoked:${s.domainName}" }
        val onHeal: suspend (StatusMessageData, OdinId, HomebaseFile) -> Unit = { _, s, _ -> log += "heal:${s.domainName}" }
    }

    @Test
    fun designationThenRevocationDispatchInOrder() = runTest {
        val calls = Calls()
        dispatchStatusMessages(
            listOf(
                statusFile(StatusMessage.EmergencyContactDesignated, "sam.example"),
                statusFile(StatusMessage.EmergencyContactRevoked, "sam.example"),
            ),
            calls.onHeal,
            calls.onDesignated,
            calls.onRevoked,
        )
        assertEquals(listOf("designated:sam.example", "revoked:sam.example"), calls.log)
    }

    @Test
    fun healGoesToItsOwnHandler() = runTest {
        val calls = Calls()
        dispatchStatusMessages(
            listOf(statusFile(StatusMessage.GroupHealRequested, "sam.example")),
            calls.onHeal,
            calls.onDesignated,
            calls.onRevoked,
        )
        assertEquals(listOf("heal:sam.example"), calls.log)
    }

    @Test
    fun otherStatusesAndOwnCopiesAreIgnored() = runTest {
        val calls = Calls()
        dispatchStatusMessages(
            listOf(
                statusFile(StatusMessage.ConversationTitleUpdated, "sam.example"),
                statusFile(StatusMessage.EmergencyContactDesignated, author = null),
            ),
            calls.onHeal,
            calls.onDesignated,
            calls.onRevoked,
        )
        assertEquals(emptyList(), calls.log)
    }

    @Test
    fun scanReturnsActiveStatusMessagesOldestFirst() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            fixture.build(scope = this)
            val drive = fixture.chatDriveId
            val newer = statusFile(StatusMessage.EmergencyContactRevoked, "sam.example", drive, createdMs = 2_000L)
            val older = statusFile(StatusMessage.EmergencyContactDesignated, "sam.example", drive, createdMs = 1_000L)
            val consumed = statusFile(
                StatusMessage.EmergencyContactDesignated,
                "frodo.example",
                drive,
                fileState = FileState.Deleted,
            )
            listOf(newer, older, consumed).forEach { fixture.insertHomebaseFile(it) }

            val scanned = activeStatusMessageFiles(fixture.dbm, fixture.testIdentityId, drive)

            assertEquals(
                listOf(older.fileMetadata.appData.uniqueId, newer.fileMetadata.appData.uniqueId),
                scanned.map { it.fileMetadata.appData.uniqueId },
            )
        }
    }

    @Test
    fun scanStartsAfterTheWatermark() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            fixture.build(scope = this)
            val drive = fixture.chatDriveId
            val before = statusFile(StatusMessage.EmergencyContactDesignated, "sam.example", drive, createdMs = 1_000L)
            val after = statusFile(StatusMessage.EmergencyContactDesignated, "frodo.example", drive, createdMs = 3_000L)
            listOf(before, after).forEach { fixture.insertHomebaseFile(it) }

            val scanned = activeStatusMessageFiles(fixture.dbm, fixture.testIdentityId, drive, UnixTimeUtc(2_000L))

            assertEquals(listOf(after.fileMetadata.appData.uniqueId), scanned.map { it.fileMetadata.appData.uniqueId })
        }
    }
}
