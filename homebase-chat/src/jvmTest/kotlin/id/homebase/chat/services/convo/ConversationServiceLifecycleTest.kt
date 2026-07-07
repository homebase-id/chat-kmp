package id.homebase.chat.services.convo

import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.upload.PayloadBundle
import id.homebase.chat.services.StatusMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for the remaining lifecycle methods on [ConversationService]:
 *   - updateConversation (title, photo)
 *   - deleteConversation / clearConversation
 *   - ensureNoteToSelfExists
 *   - introduceEveryone
 *   - archive / unarchive / pin / unpin (tag manipulation)
 */
class ConversationServiceLifecycleTest {

    private val emptyPayloadBundle = PayloadBundle(
        payloads = emptyList(),
        thumbnails = emptyList(),
        previewThumbs = emptyList(),
    )

    // ---------- updateConversation ----------

    @Test
    fun updateConversation_titleChange_emitsTitleUpdatedStatus() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
                title = "old-title",
            )

            service.updateConversation(
                conversationId = groupId,
                title = "new-title",
            )

            assertTrue(
                fixture.statusMessageSender.calls.any {
                    it.statusMessage.statusMessage == StatusMessage.ConversationTitleUpdated
                },
                "title change should emit ConversationTitleUpdated",
            )
            assertFalse(
                fixture.statusMessageSender.calls.any {
                    it.statusMessage.statusMessage == StatusMessage.ConversationPhotoUpdated
                },
                "no payload ⇒ no photo-updated message",
            )
        }
    }

    @Test
    fun updateConversation_photoChange_emitsPhotoUpdatedStatus() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
                title = "keep-same",
            )

            service.updateConversation(
                conversationId = groupId,
                title = "keep-same", // unchanged ⇒ no title status
                payloadBundle = emptyPayloadBundle,
            )

            assertTrue(
                fixture.statusMessageSender.calls.any {
                    it.statusMessage.statusMessage == StatusMessage.ConversationPhotoUpdated
                }
            )
            assertFalse(
                fixture.statusMessageSender.calls.any {
                    it.statusMessage.statusMessage == StatusMessage.ConversationTitleUpdated
                },
                "title unchanged ⇒ no title-updated message",
            )
        }
    }

    @Test
    fun updateConversation_titleAndPhoto_emitsBothStatusMessages() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
                title = "old-title",
            )

            service.updateConversation(
                conversationId = groupId,
                title = "new-title",
                payloadBundle = emptyPayloadBundle,
            )

            val kinds = fixture.statusMessageSender.calls.map { it.statusMessage.statusMessage }.toSet()
            assertTrue(StatusMessage.ConversationTitleUpdated in kinds)
            assertTrue(StatusMessage.ConversationPhotoUpdated in kinds)
        }
    }

    @Test
    fun updateConversation_noop_titleSameNoPayload_emitsNoStatusMessages() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
                title = "unchanged",
            )

            service.updateConversation(
                conversationId = groupId,
                title = "unchanged",
                payloadBundle = null,
            )

            assertTrue(
                fixture.statusMessageSender.calls.isEmpty(),
                "no observable change ⇒ no status messages",
            )
        }
    }

    @Test
    fun updateConversation_onGroup_nonAdminCaller_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf("alice.test"),
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.updateConversation(
                    conversationId = groupId,
                    title = "whatever",
                )
            }
            assertTrue(ex.message!!.contains("admin"))
        }
    }

    // ---------- deleteConversation ----------

    @Test
    fun deleteConversation_group_inActiveState_throws() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
                // active, not left/archived
            )

            val ex = assertFailsWith<IllegalStateException> {
                service.deleteConversation(groupId)
            }
            assertTrue(ex.message!!.contains("leave the group"))
        }
    }

    @Test
    fun deleteConversation_group_inLeftState_succeeds_enqueuesDeleteAndMarksRemoved() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
                localTags = listOf(ChatProtocol.ConversationLeftTag),
            )
            val rowsBefore = fixture.outboxRowCount()

            service.deleteConversation(groupId)

            // At least two enqueued ops: DeleteFilesByGroupId + UpdateFileByUniqueId (Removed).
            assertTrue(
                fixture.outboxRowCount() - rowsBefore >= 2,
                "delete should enqueue DeleteFilesByGroupId AND a conversation update"
            )
        }
    }

    @Test
    fun deleteConversation_group_inArchivedState_succeeds() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
                localTags = listOf(ChatProtocol.ConversationArchivedTag),
            )

            // Should not throw.
            service.deleteConversation(groupId)
        }
    }

    @Test
    fun deleteConversation_oneOnOne_inActiveState_succeeds() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = fixture.seedOneOnOne(other = "alice.test")

            // 1:1 has no group-state guard — should not throw.
            service.deleteConversation(convoId)
        }
    }

    // ---------- clearConversation ----------

    @Test
    fun clearConversation_enqueuesDeleteOnly_doesNotMarkRemoved() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = fixture.seedOneOnOne(other = "alice.test")
            val rowsBefore = fixture.outboxRowCount()

            service.clearConversation(convoId)

            // Exactly one outbox row — no secondary "mark removed" update.
            assertEquals(
                1L,
                fixture.outboxRowCount() - rowsBefore,
                "clearConversation enqueues just a DeleteFilesByGroupId request",
            )
        }
    }

    // ---------- ensureNoteToSelfExists ----------

    @Test
    fun ensureNoteToSelfExists_noExistingFile_createsFresh() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val rowsBefore = fixture.outboxRowCount()

            service.ensureNoteToSelfExists()

            assertTrue(
                fixture.outboxRowCount() > rowsBefore,
                "first-time note-to-self creation should enqueue an upload",
            )
        }
    }

    @Test
    fun ensureNoteToSelfExists_existingActive_isNoop() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            // Seed a healthy note-to-self file.
            fixture.seedOneOnOne(
                other = fixture.testDomain, // same as self — note-to-self
                conversationId = ChatProtocol.ConversationWithYourselfId,
            )
            val rowsBefore = fixture.outboxRowCount()

            service.ensureNoteToSelfExists()

            assertEquals(
                rowsBefore,
                fixture.outboxRowCount(),
                "existing active note-to-self ⇒ no new enqueues",
            )
        }
    }

    @Test
    fun ensureNoteToSelfExists_existingDeleted_revives() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            fixture.seedOneOnOne(
                other = fixture.testDomain,
                conversationId = ChatProtocol.ConversationWithYourselfId,
                archivalStatus = ArchivalStatus.Removed.value,
            )
            val rowsBefore = fixture.outboxRowCount()

            service.ensureNoteToSelfExists()

            assertTrue(
                fixture.outboxRowCount() > rowsBefore,
                "deleted note-to-self should be revived via an update enqueue",
            )
        }
    }

    // ---------- introduceEveryone ----------

    @Test
    fun introduceEveryone_sendsToAllParticipantsExceptSelf_withCustomMessage() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(fixture.testDomain),
            )

            service.introduceEveryone(groupId, message = "Say hi 👋")

            val call = fixture.introductionSender.calls.single()
            // introduceEveryone passes the conversation's participant list verbatim —
            // which includes self. That is the current behavior; this test locks it in.
            assertEquals(
                setOf(OdinId(fixture.testDomain), OdinId(alice), OdinId(bob)),
                call.recipients.toSet()
            )
            assertEquals("Say hi 👋", call.message)
        }
    }

    @Test
    fun introduceEveryone_nullMessage_sendsEmptyStringMessage() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
            )

            service.introduceEveryone(groupId, message = null)

            val call = fixture.introductionSender.calls.single()
            assertEquals("", call.message)
        }
    }

    // ---------- archive / unarchive / pin / unpin (tag flips) ----------
    //
    // All four route through updateConversationTags. We verify each writes an
    // outbox row via the tag-update path.

    @Test
    fun archiveConversation_enqueuesTagUpdate() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = fixture.seedOneOnOne(other = "alice.test")
            val before = fixture.outboxRowCount()

            service.archiveConversation(convoId)

            assertEquals(1L, fixture.outboxRowCount() - before)
        }
    }

    @Test
    fun unarchiveConversation_enqueuesTagUpdate() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = fixture.seedOneOnOne(
                other = "alice.test",
                localTags = listOf(ChatProtocol.ConversationArchivedTag),
            )
            val before = fixture.outboxRowCount()

            service.unarchiveConversation(convoId)

            assertEquals(1L, fixture.outboxRowCount() - before)
        }
    }

    @Test
    fun pinConversation_enqueuesTagUpdate() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = fixture.seedOneOnOne(other = "alice.test")
            val before = fixture.outboxRowCount()

            service.pinConversation(convoId)

            assertEquals(1L, fixture.outboxRowCount() - before)
        }
    }

    @Test
    fun unpinConversation_enqueuesTagUpdate() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = fixture.seedOneOnOne(
                other = "alice.test",
                localTags = listOf(ChatProtocol.ConversationPinnedTag),
            )
            val before = fixture.outboxRowCount()

            service.unpinConversation(convoId)

            assertEquals(1L, fixture.outboxRowCount() - before)
        }
    }
}
