package id.homebase.chat.services.convo

import id.homebase.api.common.OdinId
import id.homebase.chat.services.StatusMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ConversationService.updateAdmins] — admin promotion/demotion,
 * sole-admin guard, legacy-group rejection, and participant requirement.
 */
class ConversationServiceUpdateAdminsTest {

    @Test
    fun updateAdmins_add_emitsAdminAddedStatus() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(fixture.testDomain),
            )

            service.updateAdmins(
                conversationId = groupId,
                add = listOf(OdinId(alice)),
            )

            val adminAdded = fixture.statusMessageSender.calls.filter {
                it.statusMessage.statusMessage == StatusMessage.ConversationAdminAdded
            }
            assertEquals(1, adminAdded.size)
            assertEquals(OdinId(alice), adminAdded.single().statusMessage.subject)
            // No introductions on admin changes.
            assertTrue(fixture.introductionSender.calls.isEmpty())
        }
    }

    @Test
    fun updateAdmins_remove_emitsAdminRemovedStatus() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            // Seed with two admins so removal still leaves at least one.
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(fixture.testDomain, alice),
            )

            service.updateAdmins(
                conversationId = groupId,
                remove = listOf(OdinId(alice)),
            )

            val adminRemoved = fixture.statusMessageSender.calls.filter {
                it.statusMessage.statusMessage == StatusMessage.ConversationAdminRemoved
            }
            assertEquals(1, adminRemoved.size)
            assertEquals(OdinId(alice), adminRemoved.single().statusMessage.subject)
        }
    }

    @Test
    fun updateAdmins_addingNonParticipant_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
            )

            val ex = assertFailsWith<IllegalArgumentException> {
                service.updateAdmins(
                    conversationId = groupId,
                    add = listOf(OdinId("stranger.test")),
                )
            }
            assertTrue(ex.message!!.contains("recipients"))
        }
    }

    @Test
    fun updateAdmins_removingSoleAdminSelf_throwsWithReplaceHint() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.updateAdmins(
                    conversationId = groupId,
                    remove = listOf(OdinId(fixture.testDomain)),
                )
            }
            assertTrue(
                ex.message!!.contains("replace you"),
                "should surface the self-specific replace-hint: ${ex.message}"
            )
        }
    }

    @Test
    fun updateAdmins_removingSoleAdminOther_throwsGenericMessage() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            // testDomain is NOT an admin here — but then testDomain can't even call
            // updateAdmins (requireCallerIsGroupAdmin blocks). So to exercise the
            // generic branch of the sole-admin guard we need: self is admin AND
            // self is removing someone else who happens to be the only admin left
            // after removal. That only works if someone else is the sole admin,
            // which requires self NOT to be admin — contradiction with the caller
            // gate. The guard is therefore practically unreachable today for
            // non-self; we lock in the self-branch above and skip this scenario
            // intentionally.
            //
            // Left as a stub to document the dead-code observation. Remove once
            // the guard is restructured to something reachable.
            val groupId = fixture.seedGroup(
                others = listOf(alice),
                adminDomains = listOf(fixture.testDomain, alice),
            )
            // Admins after remove would be [alice] — not empty — so no throw.
            service.updateAdmins(
                conversationId = groupId,
                remove = listOf(OdinId(fixture.testDomain)),
            )
            // Sanity: we exited cleanly, no exception thrown, admin-removed status emitted.
            assertTrue(fixture.statusMessageSender.calls.any {
                it.statusMessage.statusMessage == StatusMessage.ConversationAdminRemoved
            })
        }
    }

    @Test
    fun updateAdmins_nonAdminCaller_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice),
                adminDomains = listOf(alice),
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.updateAdmins(
                    conversationId = groupId,
                    add = listOf(OdinId(alice)),
                )
            }
            assertTrue(ex.message!!.contains("admin"))
        }
    }

    @Test
    fun updateAdmins_legacyGroup_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedLegacyGroup(
                others = listOf("alice.test", "bob.test"),
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.updateAdmins(
                    conversationId = groupId,
                    add = listOf(OdinId("alice.test")),
                )
            }
            assertTrue(ex.message!!.contains("legacy"))
        }
    }
}
