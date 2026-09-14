package id.homebase.chat.services.convo

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.StatusMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ConversationService.removeGroupMember] — the single-call
 * demote-then-remove path behind the group-settings "Remove" action.
 */
class ConversationServiceRemoveMemberTest {

    @Test
    fun removeGroupMember_adminMember_demotesThenRemoves_inOneCall() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(fixture.testDomain, alice),
            )

            service.removeGroupMember(groupId, OdinId(alice))

            assertEquals(
                setOf(OdinId(fixture.testDomain)),
                service.getConversation(groupId)!!.admins,
                "the removed member must no longer hold the admin role"
            )

            // The participant drop is only observable through the removal status
            // message and the enqueued conversation-file update: updateGroupMembers
            // deliberately does not apply the new participant list to the local DB.
            val removedCalls = fixture.statusMessageSender.calls.filter {
                it.statusMessage.statusMessage == StatusMessage.ConversationMemberRemoved
            }
            assertEquals(1, removedCalls.size)
            assertEquals(OdinId(alice), removedCalls.single().statusMessage.subject)

            val rows = fixture.drainOutboxInDependencyOrder()
            assertTrue(
                rows.any { it.uniqueId == ChatProtocol.getAdminFileUniqueId(groupId) },
                "the demote must enqueue an admin-file update"
            )
            assertTrue(
                rows.any { it.uniqueId == groupId },
                "the removal must enqueue a conversation-file update"
            )
        }
    }

    @Test
    fun removeGroupMember_adminMember_emitsOnlyTheMemberRemovedStatus() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice, "bob.test"),
                adminDomains = listOf(fixture.testDomain, alice),
            )

            service.removeGroupMember(groupId, OdinId(alice))

            assertTrue(
                fixture.statusMessageSender.calls.none {
                    it.statusMessage.statusMessage == StatusMessage.ConversationAdminRemoved
                },
                "one user action must read as one system line, not a demote plus a removal"
            )
            assertEquals(
                listOf(StatusMessage.ConversationMemberRemoved),
                fixture.statusMessageSender.calls.map { it.statusMessage.statusMessage },
            )
        }
    }

    @Test
    fun removeGroupMember_lastAdmin_isRejected_withNoPartialDemote() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test", "bob.test"),
                adminDomains = listOf(fixture.testDomain),
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.removeGroupMember(groupId, OdinId(fixture.testDomain))
            }
            assertTrue(
                ex.message!!.contains("Cannot remove the last admin"),
                "the last-admin guard must be what rejects this: ${ex.message}"
            )

            assertEquals(
                setOf(OdinId(fixture.testDomain)),
                service.getConversation(groupId)!!.admins,
                "a rejected removal must not leave the member demoted"
            )
            assertEquals(0L, fixture.outboxRowCount(), "nothing may be enqueued")
            assertTrue(fixture.statusMessageSender.calls.isEmpty())
        }
    }

    @Test
    fun removeGroupMember_failedRemoval_restoresTheAdminRole() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice, "bob.test"),
                adminDomains = listOf(fixture.testDomain, alice),
            )
            fixture.statusMessageSender.failWith = RuntimeException("transit down")

            assertFailsWith<RuntimeException> {
                service.removeGroupMember(groupId, OdinId(alice))
            }

            assertEquals(
                setOf(OdinId(fixture.testDomain), OdinId(alice)),
                service.getConversation(groupId)!!.admins,
                "a removal that failed after the demote must put the admin role back"
            )
        }
    }

    @Test
    fun removeGroupMember_nonAdminMember_leavesTheAdminFileAlone() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(fixture.testDomain),
            )

            service.removeGroupMember(groupId, OdinId(bob))

            assertEquals(
                setOf(OdinId(fixture.testDomain)),
                service.getConversation(groupId)!!.admins,
            )
            assertEquals(
                listOf(StatusMessage.ConversationMemberRemoved),
                fixture.statusMessageSender.calls.map { it.statusMessage.statusMessage },
            )

            val rows = fixture.drainOutboxInDependencyOrder()
            assertTrue(
                rows.none { it.uniqueId == ChatProtocol.getAdminFileUniqueId(groupId) },
                "removing a non-admin must not write the admin file"
            )
            assertTrue(rows.any { it.uniqueId == groupId })
        }
    }

    @Test
    fun removeGroupMember_nonAdminCaller_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(alice),
            )

            val removingAnAdmin = assertFailsWith<IllegalStateException> {
                service.removeGroupMember(groupId, OdinId(alice))
            }
            assertTrue(removingAnAdmin.message!!.contains("admin"))

            val removingANonAdmin = assertFailsWith<IllegalStateException> {
                service.removeGroupMember(groupId, OdinId(bob))
            }
            assertTrue(removingANonAdmin.message!!.contains("admin"))

            assertEquals(0L, fixture.outboxRowCount())
        }
    }

    @Test
    fun removeGroupMember_legacyGroup_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedLegacyGroup(
                others = listOf("alice.test", "bob.test"),
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.removeGroupMember(groupId, OdinId("alice.test"))
            }
            assertTrue(ex.message!!.contains("legacy"))
            assertEquals(0L, fixture.outboxRowCount())
        }
    }
}
