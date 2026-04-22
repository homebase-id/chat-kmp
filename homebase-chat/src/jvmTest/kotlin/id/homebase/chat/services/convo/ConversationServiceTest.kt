package id.homebase.chat.services.convo

import id.homebase.api.common.OdinId
import id.homebase.chat.services.StatusMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Integration-ish tests for [ConversationService]. Uses an in-memory SQLite DB
 * (via [ConversationServiceTestFixture]) for the mapper/outbox, and hand-written
 * fakes for the network-shaped collaborators.
 */
class ConversationServiceTest {

    @Test
    fun updateGroupMembers_add_introducesEveryCurrentParticipantExceptSelf() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)

            val alice = "alice.test"
            val bob = "bob.test"
            val charlie = "charlie.test"

            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(fixture.testDomain),
            )

            service.updateGroupMembers(
                conversationId = groupId,
                add = listOf(OdinId(charlie)),
                remove = emptyList(),
            )

            // Introductions should go to the full post-update participant set minus
            // self. Post our refactor: existing members (alice, bob) + newly added
            // (charlie).
            assertEquals(
                1,
                fixture.introductionSender.calls.size,
                "expected one introductions call"
            )
            val group = fixture.introductionSender.calls.single()
            assertEquals(
                setOf(OdinId(alice), OdinId(bob), OdinId(charlie)),
                group.recipients.toSet(),
                "introductions must cross-introduce every non-self member, not just the newly added"
            )
            assertTrue(
                group.recipients.none { it.domainName == fixture.testDomain },
                "caller should never be in their own introduction list"
            )
        }
    }

    @Test
    fun updateGroupMembers_add_sendsMemberAddedStatusReachingNewMember() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)

            val alice = "alice.test"
            val charlie = "charlie.test"

            val groupId = fixture.seedGroup(
                others = listOf(alice),
                adminDomains = listOf(fixture.testDomain),
            )

            service.updateGroupMembers(
                conversationId = groupId,
                add = listOf(OdinId(charlie)),
                remove = emptyList(),
            )

            val memberAddedCalls = fixture.statusMessageSender.calls.filter {
                it.statusMessage.statusMessage == StatusMessage.ConversationMemberAdded
            }
            assertEquals(
                1,
                memberAddedCalls.size,
                "one ConversationMemberAdded per newly added user"
            )

            val call = memberAddedCalls.single()
            assertEquals(groupId, call.conversationId)
            assertEquals(OdinId(charlie), call.statusMessage.subject)
            assertTrue(
                call.additionalRecipients.contains(OdinId(charlie)),
                "newly added user must be in additionalRecipients so they receive the status message"
            )
        }
    }

    @Test
    fun updateGroupMembers_remove_sendsMemberRemovedStatus_noIntroductions() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)

            val alice = "alice.test"
            val bob = "bob.test"

            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(fixture.testDomain),
            )

            service.updateGroupMembers(
                conversationId = groupId,
                add = emptyList(),
                remove = listOf(OdinId(bob)),
            )

            // One status message about the removal...
            val removedCalls = fixture.statusMessageSender.calls.filter {
                it.statusMessage.statusMessage == StatusMessage.ConversationMemberRemoved
            }
            assertEquals(1, removedCalls.size)
            assertEquals(OdinId(bob), removedCalls.single().statusMessage.subject)

            // ...and no introductions, since no one was added.
            assertTrue(
                fixture.introductionSender.calls.isEmpty(),
                "removal-only update should not trigger introductions"
            )
        }
    }

    @Test
    fun updateGroupMembers_writesUpdateToOutbox() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)

            val alice = "alice.test"
            val charlie = "charlie.test"

            val groupId = fixture.seedGroup(
                others = listOf(alice),
                adminDomains = listOf(fixture.testDomain),
            )

            val rowsBefore = fixture.outboxRowCount()

            service.updateGroupMembers(
                conversationId = groupId,
                add = listOf(OdinId(charlie)),
                remove = emptyList(),
            )

            assertTrue(
                fixture.outboxRowCount() > rowsBefore,
                "updateConversationInternal should enqueue at least one outbox row"
            )
        }
    }

    @Test
    fun updateGroupMembers_removingAnAdmin_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice),
                // Both self and alice are admins.
                adminDomains = listOf(fixture.testDomain, alice),
            )

            val ex = assertFailsWith<IllegalArgumentException> {
                service.updateGroupMembers(
                    conversationId = groupId,
                    add = emptyList(),
                    remove = listOf(OdinId(alice)),
                )
            }
            assertTrue(
                ex.message!!.contains("Cannot remove admins"),
                "message should guide the caller to updateAdmins first: ${ex.message}"
            )
        }
    }

    @Test
    fun updateGroupMembers_addAndRemove_inSingleCall_emitsBothStatusMessages_introducesOnlyPostUpdateSet() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            val charlie = "charlie.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(fixture.testDomain),
            )

            service.updateGroupMembers(
                conversationId = groupId,
                add = listOf(OdinId(charlie)),
                remove = listOf(OdinId(bob)),
            )

            // One Removed + one Added
            val removed = fixture.statusMessageSender.calls.filter {
                it.statusMessage.statusMessage == StatusMessage.ConversationMemberRemoved
            }
            val added = fixture.statusMessageSender.calls.filter {
                it.statusMessage.statusMessage == StatusMessage.ConversationMemberAdded
            }
            assertEquals(1, removed.size)
            assertEquals(OdinId(bob), removed.single().statusMessage.subject)
            assertEquals(1, added.size)
            assertEquals(OdinId(charlie), added.single().statusMessage.subject)

            // Introductions sent to post-update participants (alice + charlie), NOT bob.
            val intro = fixture.introductionSender.calls.single()
            assertEquals(
                setOf(OdinId(alice), OdinId(charlie)),
                intro.recipients.toSet()
            )
        }
    }

    @Test
    fun updateGroupMembers_addingAlreadyPresentUser_isNoOp() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice),
                adminDomains = listOf(fixture.testDomain),
            )

            service.updateGroupMembers(
                conversationId = groupId,
                add = listOf(OdinId(alice)), // already a participant
                remove = emptyList(),
            )

            val addedCalls = fixture.statusMessageSender.calls.filter {
                it.statusMessage.statusMessage == StatusMessage.ConversationMemberAdded
            }
            assertTrue(
                addedCalls.isEmpty(),
                "re-adding an existing participant should not emit ConversationMemberAdded"
            )
            assertTrue(
                fixture.introductionSender.calls.isEmpty(),
                "no new participants ⇒ no introductions"
            )
        }
    }

    @Test
    fun updateGroupMembers_nonAdminCaller_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            // testDomain is a participant but NOT an admin.
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(alice),
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.updateGroupMembers(
                    conversationId = groupId,
                    add = listOf(OdinId("charlie.test")),
                )
            }
            assertTrue(ex.message!!.contains("admin"))
        }
    }

    @Test
    fun updateGroupMembers_legacyGroup_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedLegacyGroup(
                others = listOf("alice.test", "bob.test")
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.updateGroupMembers(
                    conversationId = groupId,
                    add = listOf(OdinId("charlie.test")),
                )
            }
            assertTrue(ex.message!!.contains("legacy"))
        }
    }
}
