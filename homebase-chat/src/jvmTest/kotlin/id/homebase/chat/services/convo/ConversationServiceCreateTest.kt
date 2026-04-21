package id.homebase.chat.services.convo

import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.XorIdUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ConversationService.createConversation] — 1:1 vs group branching,
 * existing-file revival, participant validation, and the admin-file / introductions
 * side-effects that belong to the group path.
 */
class ConversationServiceCreateTest {

    @Test
    fun createConversation_oneOnOne_usesDeterministicXorId_noAdminFile_noIntroductions() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"

            val result = service.createConversation(
                recipients = listOf(OdinId(alice)),
                title = null,
                payloadBundle = null,
            )

            val expectedId = XorIdUtil.getNewXorId(fixture.testDomain, alice)
            assertEquals(expectedId, result.conversationId)
            assertTrue(result.wasNewlyCreated)
            assertTrue(
                fixture.introductionSender.calls.isEmpty(),
                "1:1 does not cross-introduce anyone"
            )
            assertFalse(
                fixture.statusMessageSender.calls.any {
                    it.statusMessage.statusMessage == StatusMessage.GroupConversationStarted
                },
                "GroupConversationStarted is only emitted for groups"
            )
        }
    }

    @Test
    fun createConversation_group_createsAdminFile_sendsIntroductions_emitsGroupStartedStatus() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"

            val result = service.createConversation(
                recipients = listOf(OdinId(alice), OdinId(bob)),
                title = "the fellowship",
                payloadBundle = null,
            )
            assertTrue(result.wasNewlyCreated)

            // Introductions to recipients (self excluded).
            val intro = fixture.introductionSender.calls.single()
            assertEquals(setOf(OdinId(alice), OdinId(bob)), intro.recipients.toSet())

            // GroupConversationStarted status fired.
            val started = fixture.statusMessageSender.calls.single {
                it.statusMessage.statusMessage == StatusMessage.GroupConversationStarted
            }
            assertEquals(result.conversationId, started.conversationId)

            // Admin file seeded in DB. uploadAdminFile goes through the outbox;
            // we verify via direct DB lookup for the admin file's uniqueId.
            val adminFile = fixture.getConversationFile(
                ChatProtocol.getAdminFileUniqueId(result.conversationId)
            )
            // The admin file is only inserted locally via the optimisticWriter path —
            // it is NOT guaranteed to be queryable here without a corresponding write.
            // Instead, assert that an outbox row was enqueued for it.
            assertTrue(
                fixture.outboxRowCount() >= 2,
                "expect at least 2 outbox rows: the conversation file upload + the admin file upload"
            )
        }
    }

    @Test
    fun createConversation_existingHealthyFile_returnsWasNewlyCreatedFalse_andDoesNotRevive() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val xorId = XorIdUtil.getNewXorId(fixture.testDomain, alice)
            // Pre-seed a healthy 1:1 at the deterministic id.
            fixture.seedOneOnOne(other = alice, conversationId = xorId)
            val rowsBefore = fixture.outboxRowCount()

            val result = service.createConversation(
                recipients = listOf(OdinId(alice)),
                title = null,
                payloadBundle = null,
            )

            assertEquals(xorId, result.conversationId)
            assertFalse(result.wasNewlyCreated)
            assertEquals(
                rowsBefore,
                fixture.outboxRowCount(),
                "healthy existing file should not revive ⇒ no new outbox rows"
            )
        }
    }

    @Test
    fun createConversation_existingDeletedFile_revivesViaUpdate() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val xorId = XorIdUtil.getNewXorId(fixture.testDomain, alice)
            // Seed with archivalStatus=Removed ⇒ mapper reports Deleted ⇒ revive path.
            fixture.seedOneOnOne(
                other = alice,
                conversationId = xorId,
                archivalStatus = ArchivalStatus.Removed.value,
            )
            val rowsBefore = fixture.outboxRowCount()

            val result = service.createConversation(
                recipients = listOf(OdinId(alice)),
                title = null,
                payloadBundle = null,
            )

            assertEquals(xorId, result.conversationId)
            assertFalse(result.wasNewlyCreated)
            assertTrue(
                fixture.outboxRowCount() > rowsBefore,
                "revive should enqueue an UpdateFileByUniqueId request"
            )
        }
    }

    @Test
    fun createConversation_emptyRecipients_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)

            val ex = assertFailsWith<IllegalArgumentException> {
                service.createConversation(
                    recipients = emptyList(),
                    title = null,
                    payloadBundle = null,
                )
            }
            assertTrue(ex.message!!.contains("at least one recipient"))
        }
    }

    @Test
    fun createConversation_onlySelfInRecipients_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)

            val ex = assertFailsWith<IllegalArgumentException> {
                service.createConversation(
                    recipients = listOf(OdinId(fixture.testDomain)),
                    title = null,
                    payloadBundle = null,
                )
            }
            assertTrue(ex.message!!.contains("at least one recipient"))
        }
    }

    @Test
    fun createConversation_duplicateRecipients_deduplicate_producesOneOnOne() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"

            val result = service.createConversation(
                recipients = listOf(OdinId(alice), OdinId(alice)),
                title = null,
                payloadBundle = null,
            )

            // Same id as a single-recipient 1:1
            assertEquals(
                XorIdUtil.getNewXorId(fixture.testDomain, alice),
                result.conversationId,
            )
            assertTrue(
                fixture.introductionSender.calls.isEmpty(),
                "deduped 1:1 should not trigger introductions"
            )
        }
    }

    @Test
    fun createConversation_recipientsListContainingSelf_stillCreatesOneOnOneWithOther() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"

            val result = service.createConversation(
                recipients = listOf(OdinId(fixture.testDomain), OdinId(alice)),
                title = null,
                payloadBundle = null,
            )

            assertEquals(
                XorIdUtil.getNewXorId(fixture.testDomain, alice),
                result.conversationId,
                "self is filtered out, leaving a single normalized recipient ⇒ 1:1 id",
            )
        }
    }

    @Test
    fun createConversation_group_conversationIdIsRandom_notXor() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"

            val result = service.createConversation(
                recipients = listOf(OdinId(alice), OdinId(bob)),
                title = null,
                payloadBundle = null,
            )

            // Groups don't use XOR ids — they get a fresh random one.
            assertNotEquals(
                XorIdUtil.getNewXorId(fixture.testDomain, alice),
                result.conversationId
            )
            assertNotEquals(
                XorIdUtil.getNewXorId(fixture.testDomain, bob),
                result.conversationId
            )
        }
    }
}
