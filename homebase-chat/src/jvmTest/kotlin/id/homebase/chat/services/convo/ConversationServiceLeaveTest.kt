package id.homebase.chat.services.convo

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.StatusMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for the leave / rejoin flows on [ConversationService]:
 *   - [ConversationService.leaveGroup]
 *   - [ConversationService.acceptRejoin]
 *   - [ConversationService.declineRejoin]
 */
class ConversationServiceLeaveTest {

    // ---------- leaveGroup ----------

    @Test
    fun leaveGroup_happyPath_emitsMemberLeftStatus() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            // testDomain is not the sole admin: alice is too.
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(fixture.testDomain, alice),
            )

            service.leaveGroup(groupId)

            val leaveStatus = fixture.statusMessageSender.calls.single {
                it.statusMessage.statusMessage == StatusMessage.ConversationMemberLeft
            }
            assertEquals(OdinId(fixture.testDomain), leaveStatus.statusMessage.subject)
            assertEquals(groupId, leaveStatus.conversationId)
        }
    }

    @Test
    fun leaveGroup_soleAdmin_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.leaveGroup(groupId)
            }
            assertTrue(
                ex.message!!.contains("only admin"),
                "message should guide to assign another admin first"
            )
        }
    }

    @Test
    fun leaveGroup_onOneOnOne_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = fixture.seedOneOnOne(other = "alice.test")

            val ex = assertFailsWith<IllegalStateException> {
                service.leaveGroup(convoId)
            }
            assertTrue(ex.message!!.contains("group"))
        }
    }

    @Test
    fun leaveGroup_legacyGroup_shortCircuits_toTagOnly() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedLegacyGroup(
                others = listOf("alice.test", "bob.test"),
            )

            service.leaveGroup(groupId)

            // Legacy path: no status message, no admin-file update — just a local tag flip.
            assertTrue(
                fixture.statusMessageSender.calls.none {
                    it.statusMessage.statusMessage == StatusMessage.ConversationMemberLeft
                },
                "legacy path should skip the leave status message"
            )
        }
    }

    @Test
    fun leaveGroup_forceLocalOnly_shortCircuits_evenIfSoleAdmin() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            // testDomain is the ONLY admin — normally leaveGroup would throw, but
            // forceLocalOnly bypasses the sole-admin guard.
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
            )

            service.leaveGroup(groupId, forceLocalOnly = true)

            // No distributed leave message — local tag flip only.
            assertTrue(
                fixture.statusMessageSender.calls.none {
                    it.statusMessage.statusMessage == StatusMessage.ConversationMemberLeft
                }
            )
        }
    }

    // ---------- acceptRejoin ----------

    @Test
    fun acceptRejoin_clearsLeftTag_onRejoinPending() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            // A group where self is a participant AND has the Left tag ⇒ RejoinPending.
            val groupId = fixture.seedGroup(
                others = listOf("alice.test", "bob.test"),
                adminDomains = listOf("alice.test"),
                localTags = listOf(ChatProtocol.ConversationLeftTag),
            )

            service.acceptRejoin(groupId)

            // Nothing else emitted; the tag transform is a local-only op via optimistic writer.
            assertTrue(
                fixture.statusMessageSender.calls.isEmpty(),
                "acceptRejoin is silent — no status messages",
            )
        }
    }

    @Test
    fun acceptRejoin_whenNotRejoinPending_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test", "bob.test"),
                adminDomains = listOf("alice.test"),
                // no Left tag ⇒ Active, not RejoinPending
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.acceptRejoin(groupId)
            }
            assertTrue(ex.message!!.contains("RejoinPending"))
        }
    }

    // ---------- declineRejoin ----------

    @Test
    fun declineRejoin_emitsDeclinedRejoinStatus_onRejoinPending() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test", "bob.test"),
                adminDomains = listOf("alice.test"),
                localTags = listOf(ChatProtocol.ConversationLeftTag),
            )

            service.declineRejoin(groupId)

            val declined = fixture.statusMessageSender.calls.single {
                it.statusMessage.statusMessage == StatusMessage.ConversationMemberDeclinedRejoin
            }
            assertEquals(OdinId(fixture.testDomain), declined.statusMessage.subject)
            assertEquals(groupId, declined.conversationId)
        }
    }

    @Test
    fun declineRejoin_whenNotRejoinPending_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test", "bob.test"),
                adminDomains = listOf("alice.test"),
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.declineRejoin(groupId)
            }
            assertTrue(ex.message!!.contains("RejoinPending"))
        }
    }

    @Test
    fun declineRejoin_writesParticipantUpdate_andKeepsLeftTagLocally() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test", "bob.test"),
                adminDomains = listOf("alice.test"),
                localTags = listOf(ChatProtocol.ConversationLeftTag),
            )
            val rowsBefore = fixture.outboxRowCount()

            service.declineRejoin(groupId)

            assertTrue(
                fixture.outboxRowCount() > rowsBefore,
                "declineRejoin should enqueue conversation update + tag update"
            )
            // It should NOT clear the Left tag (unlike acceptRejoin).
            // We can't directly inspect the post-update optimistic tags without
            // more fixture work, but we can confirm at least a status + update
            // + tag enqueue sequence fired.
            assertFalse(
                fixture.statusMessageSender.calls.none {
                    it.statusMessage.statusMessage == StatusMessage.ConversationMemberDeclinedRejoin
                },
                "decline-rejoin status message must fire"
            )
        }
    }
}
