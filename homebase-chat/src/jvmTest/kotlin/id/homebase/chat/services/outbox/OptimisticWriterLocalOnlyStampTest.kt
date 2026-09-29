package id.homebase.chat.services.outbox

import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.chat.services.ChatMessageSenderServiceTestFixture
import id.homebase.chat.services.convo.ConversationLocalAppDataJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class OptimisticWriterLocalOnlyStampTest {

    private suspend fun ChatMessageSenderServiceTestFixture.seedPlaceholder(): Uuid {
        val conversationId = Uuid.random()
        optimisticWriter.writeLocalOnlyConversationPlaceholder(
            driveId = chatDriveId,
            conversationId = conversationId,
            participants = listOf(OdinId(testDomain), OdinId("alice.test")),
            isGroup = false,
        )
        return conversationId
    }

    private suspend fun ChatMessageSenderServiceTestFixture.upgradeToServerFile(conversationId: Uuid) {
        val placeholder = dbm.driveMainIndex.selectHomebaseFileByUnique(
            testIdentityId, chatDriveId, conversationId
        ) ?: error("placeholder missing")
        val real = placeholder.copy(
            fileId = Uuid.random(),
            fileMetadata = placeholder.fileMetadata.copy(
                versionTag = Uuid.random(),
                updated = UnixTimeUtc(System.currentTimeMillis()),
            ),
        )
        MainIndexMetaHelpers.HomebaseFileProcessor(dbm).baseUpsertEntryZapZap(
            identityId = testIdentityId,
            driveId = chatDriveId,
            fileHeaders = listOf(real),
            cursor = null,
        )
    }

    @Test
    fun localOnlyPlaceholderStamp_updatesLocalRowAndEnqueuesNothing() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            fixture.build(scope = this)
            val conversationId = fixture.seedPlaceholder()
            val readAt = UnixTimeUtc(1_700_000_000_000)

            val request = fixture.optimisticWriter.stampConversationLastReadTime(
                fixture.chatDriveId, conversationId, readAt
            )

            assertNull(request)
            val row = fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                fixture.testIdentityId, fixture.chatDriveId, conversationId
            ) ?: error("row missing")
            val local = OdinSystemSerializer.deserialize<ConversationLocalAppDataJson>(
                assertNotNull(row.fileMetadata.localAppData?.content)
            )
            assertEquals(readAt, local.lastReadTime)
            assertNull(row.fileMetadata.versionTag)
            assertEquals(0, fixture.dbm.outbox.count())
        }
    }

    @Test
    fun rowWithVersionTag_stillEnqueues() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            fixture.build(scope = this)
            val conversationId = fixture.seedPlaceholder()
            fixture.upgradeToServerFile(conversationId)

            val request = fixture.optimisticWriter.stampConversationLastReadTime(
                fixture.chatDriveId, conversationId, UnixTimeUtc(1_700_000_000_000)
            )

            assertNotNull(request)
        }
    }

    @Test
    fun placeholderUpgradedLater_enqueuesOnNextStamp() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            fixture.build(scope = this)
            val conversationId = fixture.seedPlaceholder()

            assertNull(
                fixture.optimisticWriter.stampConversationLastReadTime(
                    fixture.chatDriveId, conversationId, UnixTimeUtc(1_700_000_000_000)
                )
            )
            assertEquals(true, fixture.optimisticWriter.isLocalOnlyConversation(fixture.chatDriveId, conversationId))

            fixture.upgradeToServerFile(conversationId)

            val row = fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                fixture.testIdentityId, fixture.chatDriveId, conversationId
            ) ?: error("row missing")
            assertNotNull(row.fileMetadata.versionTag)
            assertEquals(false, fixture.optimisticWriter.isLocalOnlyConversation(fixture.chatDriveId, conversationId))

            val request = fixture.optimisticWriter.stampConversationLastReadTime(
                fixture.chatDriveId, conversationId, UnixTimeUtc(1_700_000_001_000)
            )
            assertNotNull(request)
            assertEquals(row.fileId, request.fileId)
        }
    }
}
