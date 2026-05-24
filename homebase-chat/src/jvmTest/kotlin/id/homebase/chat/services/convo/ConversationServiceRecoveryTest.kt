package id.homebase.chat.services.convo

import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.XorIdUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Tier-3 coverage for [ConversationService.recoverConversation] and the admin
 * accessors ([ConversationService.getAdmins],
 * [ConversationService.getConversationAdminHomebaseFile]).
 */
class ConversationServiceRecoveryTest {

    // ---------- recoverConversation ----------

    @Test
    fun recoverConversation_noteToSelf_delegatesToEnsureNoteToSelfExists() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val rowsBefore = fixture.outboxRowCount()

            service.recoverConversation(
                conversationId = ChatProtocol.ConversationWithYourselfId,
                originalAuthor = OdinId(fixture.testDomain),
            )

            // No existing note-to-self file ⇒ ensureNoteToSelfExists creates + pins.
            assertTrue(
                fixture.outboxRowCount() > rowsBefore,
                "note-to-self recovery should enqueue a creation/pin",
            )
        }
    }

    @Test
    fun recoverConversation_oneOnOne_existingActive_isNoOp() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val xorId = XorIdUtil.getNewXorId(fixture.testDomain, alice)
            fixture.seedOneOnOne(other = alice, conversationId = xorId)
            val rowsBefore = fixture.outboxRowCount()

            service.recoverConversation(
                conversationId = xorId,
                originalAuthor = OdinId(alice),
            )

            assertEquals(
                rowsBefore,
                fixture.outboxRowCount(),
                "healthy active 1:1 needs no recovery ⇒ no new outbox rows",
            )
        }
    }

    @Test
    fun recoverConversation_oneOnOne_existingDeleted_revivesAsLocalPlaceholder() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val xorId = XorIdUtil.getNewXorId(fixture.testDomain, alice)
            fixture.seedOneOnOne(
                other = alice,
                conversationId = xorId,
                archivalStatus = ArchivalStatus.Removed.value,
            )
            val rowsBefore = fixture.outboxRowCount()

            service.recoverConversation(
                conversationId = xorId,
                originalAuthor = OdinId(alice),
            )

            assertEquals(
                rowsBefore,
                fixture.outboxRowCount(),
                "recovery is local-only ⇒ no outbox enqueue",
            )
            val file = fixture.getConversationFile(xorId)
            assertNotNull(file, "deleted 1:1 should be replaced with a local placeholder")
            assertNull(
                file.fileMetadata.versionTag,
                "local-only placeholder marker (versionTag=null) lets a real server file later supersede it",
            )
            assertTrue(
                fixture.conversationLoader.loaded.contains(xorId),
                "recoverConversation should refresh the in-memory model via loadConversation",
            )
        }
    }

    @Test
    fun recoverConversation_oneOnOne_noFile_writesLocalPlaceholder() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val xorId = XorIdUtil.getNewXorId(fixture.testDomain, alice)
            val rowsBefore = fixture.outboxRowCount()

            service.recoverConversation(
                conversationId = xorId,
                originalAuthor = OdinId(alice),
            )

            assertEquals(
                rowsBefore,
                fixture.outboxRowCount(),
                "recovery is local-only ⇒ no outbox enqueue",
            )
            val file = fixture.getConversationFile(xorId)
            assertNotNull(file, "no local file ⇒ a placeholder should be written")
            assertNull(file.fileMetadata.versionTag, "placeholder marker")
            assertTrue(fixture.conversationLoader.loaded.contains(xorId))
        }
    }

    @Test
    fun recoverConversation_oneOnOne_forwardedMessage_usesSenderAsCounterparty() = runTest {
        // Forwarded-message scenario:
        //   senderOdinId = alice (the wire-level counterparty in this 1:1)
        //   originalAuthor = carol (whoever first wrote the content; not in this 1:1)
        // The 1:1 id is XorId(self, alice). The XOR test must use sender, not
        // originalAuthor — otherwise the convo is misclassified as a group
        // ("1:1 repair" forced fallback) with the wrong participant.
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val carol = "carol.test"
            val xorId = XorIdUtil.getNewXorId(fixture.testDomain, alice)

            service.recoverConversation(
                conversationId = xorId,
                originalAuthor = OdinId(carol),
                sender = OdinId(alice),
            )

            val file = fixture.getConversationFile(xorId)
            assertNotNull(file, "forwarded 1:1 should produce a placeholder file")
            assertNull(file.fileMetadata.versionTag, "local-only placeholder marker")

            // Took the 1:1 branch (no ConversationGroupTag) — the regression
            // would set the group tag and a "1:1 repair" title.
            val tags = file.fileMetadata.appData.tags ?: emptyList()
            assertFalse(
                tags.contains(ChatProtocol.ConversationGroupTag),
                "forwarded 1:1 should NOT be classified as a group",
            )

            // Recipients use the sender (alice), not the originalAuthor (carol).
            val contentJson = file.fileMetadata.appData.content
            assertNotNull(contentJson, "placeholder content should be written")
            val content = OdinSystemSerializer.deserialize<ConversationAppDataJson>(contentJson)
            val recipients = content.recipients.filterNotNull().map { it.domainName }
            assertTrue(
                recipients.contains(alice),
                "1:1 placeholder must list the sender (alice) as participant; got $recipients",
            )
            assertFalse(
                recipients.contains(carol),
                "originalAuthor (carol) is content provenance, not a participant; got $recipients",
            )
        }
    }

    /**
     * An own-authored, unmappable (self-only `recipients=[self]`), message-less
     * header is a dead file on our OWN drive that local-only recovery can never fix:
     * deleting just the local row lets the next sync re-download it, looping forever
     * (FAILED map → recover → deleteBy(local) → "1:1 repair" → re-sync → repeat).
     *
     * Pins the loop-breaker: recovery hard-deletes the file from our own server drive
     * (`recipients=null` ⇒ no peer fan-out), drops the local row, and writes NO
     * placeholder so the dead thread disappears.
     */
    @Test
    fun recoverConversation_ownAuthoredSelfOnlyInvalid_noMessages_hardDeletesServerHeader() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            // The orphan shape: recipients=[testDomain only], authored by us, no messages.
            val convoId = fixture.seedOrphanedOneOnOneSelfOnly()
            val brokenFileId = fixture.getConversationFile(convoId)!!.fileId

            service.recoverConversation(
                conversationId = convoId,
                originalAuthor = OdinId(fixture.testDomain),
            )

            val deletes = fixture.drainOutbox().mapNotNull { it.asHardDeleteRequestOrNull() }
            assertEquals(1, deletes.size, "exactly one own-drive hard-delete should be enqueued; got $deletes")
            val del = deletes.single()
            assertEquals(
                listOf(brokenFileId),
                del.fileIds,
                "hard-delete must target the broken header's fileId",
            )
            assertNull(del.recipients, "own-drive delete must have no recipients (no peer fan-out)")
            assertTrue(del.hardDelete, "must be a hard delete so the next peer push lands as a fresh create")

            assertNull(
                fixture.getConversationFile(convoId),
                "local row must be gone so the dead thread disappears (no placeholder written)",
            )
            assertTrue(
                fixture.conversationLoader.removed.contains(convoId),
                "recoverConversation should drop the in-memory model via removeConversation",
            )
        }
    }

    /**
     * Guard: a self-only Invalid header that we authored but which DOES have a peer
     * message in its group is recoverable — we must NOT hard-delete it (that would
     * strand the peer's messages). Falls back to the existing local-placeholder path.
     */
    @Test
    fun recoverConversation_ownAuthoredSelfOnlyInvalid_withPeerMessage_doesNotDelete() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = fixture.seedOrphanedOneOnOneSelfOnly()
            fixture.seedMessage(groupId = convoId, author = "alice.test")

            service.recoverConversation(
                conversationId = convoId,
                originalAuthor = OdinId(fixture.testDomain),
            )

            val deletes = fixture.drainOutbox().mapNotNull { it.asHardDeleteRequestOrNull() }
            assertTrue(deletes.isEmpty(), "recoverable header (has a peer message) must NOT be hard-deleted; got $deletes")
            assertNotNull(
                fixture.getConversationFile(convoId),
                "recoverable header keeps a local placeholder",
            )
        }
    }

    /**
     * Ownership gate: a self-only Invalid header authored by a PEER (not us) must
     * never be hard-deleted from our drive — we only ever remove files we authored.
     */
    @Test
    fun recoverConversation_peerAuthoredSelfOnlyInvalid_doesNotDelete() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = fixture.seedOrphanedOneOnOneSelfOnly(originalAuthor = "alice.test")

            service.recoverConversation(
                conversationId = convoId,
                originalAuthor = OdinId("alice.test"),
            )

            val deletes = fixture.drainOutbox().mapNotNull { it.asHardDeleteRequestOrNull() }
            assertTrue(deletes.isEmpty(), "peer-authored header must NOT be hard-deleted from our drive; got $deletes")
        }
    }

    @Test
    fun recoverConversation_group_noFile_writesLocalPlaceholder() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            // Random uuid that does NOT match XorIdUtil(domain, alice) ⇒ group branch.
            val groupId = Uuid.random()
            val rowsBefore = fixture.outboxRowCount()

            service.recoverConversation(
                conversationId = groupId,
                originalAuthor = OdinId(alice),
            )

            assertEquals(
                rowsBefore,
                fixture.outboxRowCount(),
                "recovery is local-only ⇒ no outbox enqueue",
            )
            val file = fixture.getConversationFile(groupId)
            assertNotNull(file, "no local file ⇒ a placeholder should be written")
            assertNull(file.fileMetadata.versionTag, "placeholder marker")
            assertTrue(fixture.conversationLoader.loaded.contains(groupId))
        }
    }

    // ---------- getAdmins ----------

    @Test
    fun getAdmins_returnsAdminsFromAdminFile_whenPresent() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(alice, bob),
            )

            val admins = service.getAdmins(groupId)

            assertEquals(setOf(OdinId(alice), OdinId(bob)), admins)
        }
    }

    @Test
    fun getAdmins_fallsBackToOriginalAuthor_whenNoAdminFile() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            // seedGroup with seedAdminFile = false skips the dedicated admin file.
            val groupId = fixture.seedGroup(
                others = listOf(alice),
                adminDomains = listOf(fixture.testDomain),
                seedAdminFile = false,
            )

            val admins = service.getAdmins(groupId)

            // originalAuthor on the seeded conversation is testDomain.
            assertEquals(setOf(OdinId(fixture.testDomain)), admins)
        }
    }

    // ---------- getConversationAdminHomebaseFile ----------

    @Test
    fun getConversationAdminHomebaseFile_returnsNull_whenNoAdminFile() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
                seedAdminFile = false,
            )

            val adminFile = service.getConversationAdminHomebaseFile(groupId)

            assertNull(adminFile)
        }
    }

    @Test
    fun getConversationAdminHomebaseFile_returnsFile_whenPresent() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val groupId = fixture.seedGroup(
                others = listOf("alice.test"),
                adminDomains = listOf(fixture.testDomain),
                // seedAdminFile defaults to true
            )

            val adminFile = service.getConversationAdminHomebaseFile(groupId)

            assertNotNull(adminFile)
            assertEquals(
                ChatProtocol.ConversationAdminFileType,
                adminFile.fileMetadata.appData.fileType,
            )
        }
    }

    // ---------- small accessors ----------

    @Test
    fun getConversation_returnsNull_forUnknownId() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)

            val result = service.getConversation(Uuid.random())

            assertNull(result)
        }
    }

    @Test
    fun requireConversation_throws_forUnknownId() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)

            val ex = assertFailsWith<IllegalStateException> {
                service.requireConversation(Uuid.random())
            }
            assertTrue(ex.message!!.contains("No conversation"))
        }
    }
}
