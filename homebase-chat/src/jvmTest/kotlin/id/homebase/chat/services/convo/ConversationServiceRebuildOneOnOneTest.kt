package id.homebase.chat.services.convo

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.XorIdUtil
import id.homebase.chat.services.convo.ConversationService.OneOnOneRebuildOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class ConversationServiceRebuildOneOnOneTest {

    private val alice = "alice.test"

    private suspend fun ConversationServiceTestFixture.seedPlaceholder(
        conversationId: Uuid,
        participants: List<String>,
        isGroup: Boolean = false,
        created: UnixTimeUtc? = null,
    ) {
        optimisticWriter.writeLocalOnlyConversationPlaceholder(
            driveId = chatDriveId,
            conversationId = conversationId,
            participants = participants.map(::OdinId),
            isGroup = isGroup,
        )
        if (created != null) {
            val placeholder = getConversationFile(conversationId)!!
            dbm.driveMainIndex.deleteBy(testIdentityId, chatDriveId, placeholder.fileId)
            insertHomebaseFile(
                placeholder.copy(fileMetadata = placeholder.fileMetadata.copy(created = created))
            )
        }
    }

    private suspend fun ConversationServiceTestFixture.uploadRequestFor(uniqueId: Uuid): UploadFileRequest {
        val row = drainOutboxInDependencyOrder().single { it.uniqueId == uniqueId }
        assertEquals(DriveOutboxUploader.UploadNewFile, row.uploadType)
        return OdinSystemSerializer.deserialize<UploadFileRequest>(row.json.decodeToString())
    }

    private fun HomebaseFile.recipients(): List<String> =
        OdinSystemSerializer.deserialize<ConversationAppDataJson>(fileMetadata.appData.content!!)
            .recipients.filterNotNull().map { it.domainName }

    @Test
    fun createConversation_onLocalOnlyPlaceholder_enqueuesNothing() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val id = XorIdUtil.getNewXorId(fixture.testDomain, alice)
            fixture.seedPlaceholder(id, listOf(fixture.testDomain, alice))

            val result = service.createConversation(listOf(OdinId(alice)), null, null)

            assertFalse(result.wasNewlyCreated)
            assertEquals(0L, fixture.outboxRowCount(), "the existing path cannot bring a missing 1:1 back")
        }
    }

    @Test
    fun localOnlyPlaceholder_isRecreatedUnderDerivedId_andSentToPeer() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val id = XorIdUtil.getNewXorId(fixture.testDomain, alice)
            val originalCreated = UnixTimeUtc(1_700_000_000_000)
            fixture.seedPlaceholder(id, listOf(fixture.testDomain, alice), created = originalCreated)
            val messageFileId = Uuid.random()
            fixture.seedMessage(groupId = id, author = alice, fileId = messageFileId)

            val result = service.rebuildOneOnOneConversation(id)

            assertEquals(OneOnOneRebuildOutcome.Rebuilt, result.outcome)
            assertEquals(OdinId(alice), result.peer)

            val local = assertNotNull(fixture.getConversationFile(id))
            assertEquals(originalCreated, local.fileMetadata.created)
            assertEquals(setOf(fixture.testDomain, alice), local.recipients().toSet())
            assertTrue(ChatProtocol.isPendingSendTag in local.fileMetadata.localAppData?.tags.orEmpty())
            assertNotNull(
                fixture.dbm.driveMainIndex.selectByIdentityAndDriveAndFile(
                    fixture.testIdentityId, fixture.chatDriveId, messageFileId
                ),
                "messages are separate files and must survive the rebuild",
            )

            val request = fixture.uploadRequestFor(id)
            assertEquals(id, request.metadata.appData.uniqueId)
            assertNull(request.metadata.appData.tags, "a 1:1 carries no group tag")
            assertEquals(listOf(alice), request.transitOptions?.recipients?.map { it.domainName })
        }
    }

    @Test
    fun serverHeader404_rebuildsEvenThoughLocalRowHasVersionTag() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val lookups = mutableListOf<Uuid>()
            val service = fixture.build(scope = this, serverConversationHeader = { lookups += it; null })
            val id = fixture.seedOneOnOne(other = alice, conversationId = XorIdUtil.getNewXorId(fixture.testDomain, alice))

            val result = service.rebuildOneOnOneConversation(id)

            assertEquals(OneOnOneRebuildOutcome.Rebuilt, result.outcome)
            assertEquals(listOf(id), lookups)
            assertEquals(listOf(alice), fixture.uploadRequestFor(id).transitOptions?.recipients?.map { it.domainName })
        }
    }

    @Test
    fun serverStillHasFile_isLeftAlone() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            lateinit var onServer: HomebaseFile
            val service = fixture.build(scope = this, serverConversationHeader = { onServer })
            val id = XorIdUtil.getNewXorId(fixture.testDomain, alice)
            fixture.seedPlaceholder(id, listOf(fixture.testDomain, alice))
            onServer = fixture.getConversationFile(id)!!

            val result = service.rebuildOneOnOneConversation(id)

            assertEquals(OneOnOneRebuildOutcome.AlreadyOnServer, result.outcome)
            assertEquals(0L, fixture.outboxRowCount())
            assertEquals(onServer.fileId, fixture.getConversationFile(id)?.fileId)
        }
    }

    @Test
    fun healthyLocalRow_withoutServerLookup_isLeftAlone() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val id = fixture.seedOneOnOne(other = alice, conversationId = XorIdUtil.getNewXorId(fixture.testDomain, alice))

            assertEquals(OneOnOneRebuildOutcome.AlreadyOnServer, service.rebuildOneOnOneConversation(id).outcome)
            assertEquals(0L, fixture.outboxRowCount())
        }
    }

    @Test
    fun secondRebuildWhileFirstIsQueued_doesNotCreateAgain() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this, serverConversationHeader = { null })
            val id = XorIdUtil.getNewXorId(fixture.testDomain, alice)
            fixture.seedPlaceholder(id, listOf(fixture.testDomain, alice))

            assertEquals(OneOnOneRebuildOutcome.Rebuilt, service.rebuildOneOnOneConversation(id).outcome)
            val fileId = fixture.getConversationFile(id)?.fileId

            assertEquals(OneOnOneRebuildOutcome.AlreadyQueued, service.rebuildOneOnOneConversation(id).outcome)
            assertEquals(1L, fixture.outboxRowCount())
            assertEquals(fileId, fixture.getConversationFile(id)?.fileId)
        }
    }

    @Test
    fun group_isRejected_andUntouched() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this, serverConversationHeader = { null })
            val id = Uuid.random()
            fixture.seedPlaceholder(id, listOf(fixture.testDomain, alice, "bob.test"), isGroup = true)
            val before = fixture.getConversationFile(id)?.fileId

            assertFailsWith<IllegalArgumentException> { service.rebuildOneOnOneConversation(id) }

            assertEquals(0L, fixture.outboxRowCount())
            assertEquals(before, fixture.getConversationFile(id)?.fileId)
        }
    }

    @Test
    fun twoParticipantGroup_isRejected() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this, serverConversationHeader = { null })
            val id = XorIdUtil.getNewXorId(fixture.testDomain, alice)
            fixture.seedPlaceholder(id, listOf(fixture.testDomain, alice), isGroup = true)

            assertFailsWith<IllegalArgumentException> { service.rebuildOneOnOneConversation(id) }
            assertEquals(0L, fixture.outboxRowCount())
        }
    }

    @Test
    fun oneOnOneWhoseIdIsNotDerived_isRejected() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this, serverConversationHeader = { null })
            val id = Uuid.random()
            fixture.seedPlaceholder(id, listOf(fixture.testDomain, alice))

            assertFailsWith<IllegalArgumentException> { service.rebuildOneOnOneConversation(id) }
            assertEquals(0L, fixture.outboxRowCount())
        }
    }
}
