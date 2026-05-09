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
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.GroupHealInfo
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Tier-3 coverage for [ConversationService.handleIncomingHealRequest].
 *
 * The handler is the receive-side of the group-heal protocol. It runs when a
 * [StatusMessage.GroupHealRequested] status message arrives from the canonical
 * author of a group. The handler must:
 *
 *   1. Detect both **broken** (file present but mismatched author/versionTag)
 *      and **missing** (no local row at all) states. The pre-hardening
 *      implementation collapsed missing into "no-op" because mainFile==null
 *      short-circuited the && isFileBroken check, leaving recipients
 *      permanently stuck after markHealApplied tagged the status message.
 *   2. Hard-delete *only* the broken local files (a missing file has no
 *      fileId to act on and the server already lacks the row).
 *   3. Write a self-sufficient local placeholder (versionTag=null) for both
 *      the main and admin files so the UI behaves as if nothing was broken
 *      while the canonical author's peer push is in flight.
 *   4. Skip the admin placeholder when the heal payload omits
 *      [GroupHealInfo.canonicalAdmins] (legacy senders) — fall back to the
 *      delete-only path so getAdmins()' originalAuthor fallback keeps things
 *      functional.
 *   5. Stay no-op on a second pass once the placeholder is in place
 *      (placeholder has versionTag=null, isFileBroken returns false).
 */
class ConversationServiceHealTest {

    private val canonicalAuthorDomain = "alice.test"

    @Test
    fun mainMissing_writesPlaceholderFromCanonicalParticipants_andDoesNotEnqueueHardDelete() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            val canonicalParticipants = listOf(
                OdinId(canonicalAuthorDomain),
                OdinId(fixture.testDomain),
                OdinId("bob.test"),
            )
            val outboxRowsBefore = fixture.outboxRowCount()

            service.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    canonicalAuthor = canonicalAuthorDomain,
                    canonicalParticipants = canonicalParticipants,
                    canonicalAdmins = listOf(OdinId(canonicalAuthorDomain)),
                ),
                sender = OdinId(canonicalAuthorDomain),
                messageFile = stubStatusMessageFile(fixture.chatDriveId),
            )

            // Missing file ⇒ no DeleteLocalFilesByFileIdRequest enqueued.
            val drained = fixture.drainOutbox()
            val deleteRequests = drained.filter { it.isHardDeleteRequest() }
            assertTrue(
                deleteRequests.isEmpty(),
                "missing file ⇒ NO hard-delete should be enqueued; got $deleteRequests",
            )
            // We may have enqueued the markHealApplied tag-update — that's fine,
            // the assertion above scoped to the hard-delete shape only.

            // Main placeholder file should now exist with versionTag=null and
            // canonical participants in its content.
            val mainFile = fixture.getConversationFile(convoId)
            assertNotNull(mainFile, "missing main file must be replaced by a local placeholder")
            assertNull(mainFile.fileMetadata.versionTag, "placeholder marker (versionTag=null)")
            val storedRecipients = readPlaceholderRecipients(mainFile)
            assertEquals(
                canonicalParticipants.map { it.domainName }.toSet(),
                storedRecipients.toSet(),
                "placeholder must list all canonicalParticipants from the heal payload",
            )

            // The conversation list should have been refreshed via loadConversation.
            assertTrue(
                fixture.conversationLoader.loaded.contains(convoId),
                "loadConversation must be called to refresh the in-memory model after heal",
            )
            assertTrue(
                fixture.outboxRowCount() >= outboxRowsBefore,
                "outbox row count should not decrease",
            )
        }
    }

    @Test
    fun mainBroken_hardDeletesLocalFile_andWritesPlaceholderFromCanonicalIdentity() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            // Seed a "broken" main file: originalAuthor = us, but the canonical
            // author claims to be alice. isFileBroken returns true (author mismatch).
            fixture.seedGroup(
                conversationId = convoId,
                others = listOf(canonicalAuthorDomain, "bob.test"),
                adminDomains = listOf(fixture.testDomain),
                seedAdminFile = false,
            )
            val brokenMainFile = fixture.getConversationFile(convoId)
            assertNotNull(brokenMainFile, "precondition: seeded main file present")
            val brokenMainFileId = brokenMainFile.fileId

            val canonicalParticipants = listOf(
                OdinId(canonicalAuthorDomain),
                OdinId(fixture.testDomain),
                OdinId("bob.test"),
            )

            service.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    canonicalAuthor = canonicalAuthorDomain,
                    canonicalParticipants = canonicalParticipants,
                    canonicalAdmins = listOf(OdinId(canonicalAuthorDomain)),
                ),
                sender = OdinId(canonicalAuthorDomain),
                messageFile = stubStatusMessageFile(fixture.chatDriveId),
            )

            // Hard-delete request enqueued targeting the broken main fileId.
            val drained = fixture.drainOutbox()
            val deletes = drained.mapNotNull { it.asHardDeleteRequestOrNull() }
            assertTrue(
                deletes.any { req -> req.fileIds.contains(brokenMainFileId) && req.hardDelete },
                "broken main file ⇒ hardDelete=true request for fileId=$brokenMainFileId; got $deletes",
            )

            // Placeholder written in place: same uniqueId, versionTag=null, new fileId.
            val placeholder = fixture.getConversationFile(convoId)
            assertNotNull(placeholder, "broken row must be replaced with a placeholder, not deleted outright")
            assertNull(placeholder.fileMetadata.versionTag, "placeholder marker")
            assertFalse(
                placeholder.fileId == brokenMainFileId,
                "placeholder fileId must differ from the broken row's fileId",
            )
        }
    }

    @Test
    fun adminMissing_withCanonicalAdmins_writesAdminPlaceholder() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            val canonicalAdmins = listOf(
                OdinId(canonicalAuthorDomain),
                OdinId("bob.test"),
            )
            // Seed only the main file so isFileBroken on main returns false (versionTag matches null
            // canonical) — but we want the main path to also be triggered to compare. Simpler: leave
            // both files missing; both branches fire.

            service.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    canonicalAuthor = canonicalAuthorDomain,
                    canonicalParticipants = listOf(
                        OdinId(canonicalAuthorDomain),
                        OdinId(fixture.testDomain),
                    ),
                    canonicalAdmins = canonicalAdmins,
                ),
                sender = OdinId(canonicalAuthorDomain),
                messageFile = stubStatusMessageFile(fixture.chatDriveId),
            )

            // Admin placeholder file should now exist at the deterministic admin uniqueId.
            val adminUniqueId = ChatProtocol.getAdminFileUniqueId(convoId)
            val adminPlaceholder = fixture.dbm.driveMainIndex
                .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, adminUniqueId)
            assertNotNull(adminPlaceholder, "missing admin file must be replaced with a local admin placeholder")
            assertNull(adminPlaceholder.fileMetadata.versionTag, "admin placeholder marker (versionTag=null)")
            assertEquals(
                ChatProtocol.ConversationAdminFileType,
                adminPlaceholder.fileMetadata.appData.fileType,
                "admin placeholder must be tagged as the admin file type so getAdmins() reads it",
            )

            // Content should contain the canonical admins so getAdmins() returns the
            // full set instead of falling back to originalAuthor.
            val storedAdmins = readAdminPlaceholderAdmins(adminPlaceholder)
            assertEquals(
                canonicalAdmins.map { it.domainName }.toSet(),
                storedAdmins.toSet(),
                "admin placeholder content must list every canonicalAdmin from the heal payload",
            )
        }
    }

    @Test
    fun adminMissing_legacyPayloadWithoutCanonicalAdmins_doesNotWriteAdminPlaceholder() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            // Both main and admin missing; legacy payload (canonicalAdmins=null).

            service.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    canonicalAuthor = canonicalAuthorDomain,
                    canonicalParticipants = listOf(
                        OdinId(canonicalAuthorDomain),
                        OdinId(fixture.testDomain),
                    ),
                    canonicalAdmins = null, // legacy sender
                ),
                sender = OdinId(canonicalAuthorDomain),
                messageFile = stubStatusMessageFile(fixture.chatDriveId),
            )

            // Main placeholder still gets written (we always have title+participants).
            val mainPlaceholder = fixture.getConversationFile(convoId)
            assertNotNull(mainPlaceholder, "main placeholder must still be written even on legacy payloads")

            // Admin placeholder must NOT be written — we have no canonicalAdmins to seed it.
            val adminUniqueId = ChatProtocol.getAdminFileUniqueId(convoId)
            val adminPlaceholder = fixture.dbm.driveMainIndex
                .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, adminUniqueId)
            assertNull(
                adminPlaceholder,
                "legacy heal payload (canonicalAdmins=null) must NOT fabricate an admin placeholder; " +
                        "fall back to delete-only path with getAdmins() originalAuthor fallback",
            )
        }
    }

    @Test
    fun secondPass_afterPlaceholderWritten_isNoOp() = runTest {
        // First pass writes a versionTag=null placeholder. Second pass (a
        // different status message file id, simulating a re-fired heal from
        // the canonical author after they updated something else) reads
        // isFileBroken → false (placeholder versionTag=null is the explicit
        // "leave me alone" marker) and takes the no-op branch. No additional
        // hard-deletes or placeholders should be emitted.
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            val canonicalParticipants = listOf(
                OdinId(canonicalAuthorDomain),
                OdinId(fixture.testDomain),
            )

            // ---- pass 1: missing → placeholder
            service.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    canonicalAuthor = canonicalAuthorDomain,
                    canonicalParticipants = canonicalParticipants,
                    canonicalAdmins = listOf(OdinId(canonicalAuthorDomain)),
                ),
                sender = OdinId(canonicalAuthorDomain),
                messageFile = stubStatusMessageFile(fixture.chatDriveId),
            )
            val placeholderAfterPass1 = fixture.getConversationFile(convoId)
            assertNotNull(placeholderAfterPass1, "pass 1 must write a placeholder")
            val placeholderFileIdAfterPass1 = placeholderAfterPass1.fileId
            // Drain anything pass 1 enqueued so we can isolate pass 2.
            fixture.drainOutbox()
            val sendCallsAfterPass1 = fixture.statusMessageSender.calls.size

            // ---- pass 2: same canonical identity, different status message file id
            service.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    canonicalAuthor = canonicalAuthorDomain,
                    canonicalParticipants = canonicalParticipants,
                    canonicalAdmins = listOf(OdinId(canonicalAuthorDomain)),
                ),
                sender = OdinId(canonicalAuthorDomain),
                messageFile = stubStatusMessageFile(fixture.chatDriveId),
            )

            // Placeholder still in place, fileId unchanged → no rewrite happened.
            val placeholderAfterPass2 = fixture.getConversationFile(convoId)
            assertNotNull(placeholderAfterPass2, "placeholder must still be present after pass 2")
            assertEquals(
                placeholderFileIdAfterPass1,
                placeholderAfterPass2.fileId,
                "pass 2 must NOT rewrite the placeholder — versionTag=null is the leave-alone marker",
            )

            // No hard-delete request was enqueued in pass 2.
            val drainedPass2 = fixture.drainOutbox()
            val deletesPass2 = drainedPass2.mapNotNull { it.asHardDeleteRequestOrNull() }
            assertTrue(
                deletesPass2.isEmpty(),
                "pass 2 must take the no-op branch and NOT enqueue any hard-delete; got $deletesPass2",
            )

            // No GroupHealLocalCleanup status was emitted on pass 2 (no-op branch).
            assertEquals(
                sendCallsAfterPass1,
                fixture.statusMessageSender.calls.size,
                "pass 2 must NOT send a GroupHealLocalCleanup status — that would spam the user every replay",
            )
        }
    }

    @Test
    fun selfLoopback_skipsHandling() = runTest {
        // Sender is us — we're seeing our own outgoing heal status loop back.
        // The handler must short-circuit before any cleanup work.
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            // Seed a "broken" file so a non-self-loopback heal would have
            // hard-deleted it. We assert that nothing happens.
            fixture.seedGroup(
                conversationId = convoId,
                others = listOf("bob.test"),
                adminDomains = listOf(fixture.testDomain),
                seedAdminFile = false,
            )
            val seededFileId = fixture.getConversationFile(convoId)?.fileId
            assertNotNull(seededFileId)

            service.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    // Canonical author is us → self-loopback.
                    canonicalAuthor = fixture.testDomain,
                    canonicalParticipants = listOf(OdinId(fixture.testDomain), OdinId("bob.test")),
                    canonicalAdmins = listOf(OdinId(fixture.testDomain)),
                ),
                sender = OdinId(fixture.testDomain),
                messageFile = stubStatusMessageFile(fixture.chatDriveId),
            )

            // File untouched.
            assertEquals(seededFileId, fixture.getConversationFile(convoId)?.fileId)
            // No outbox enqueue.
            val drained = fixture.drainOutbox()
            val deletes = drained.mapNotNull { it.asHardDeleteRequestOrNull() }
            assertTrue(deletes.isEmpty(), "self-loopback must NOT enqueue any hard-delete")
        }
    }

    @Test
    fun forgedSender_skipsHandling() = runTest {
        // Sender's domain doesn't match the canonical author — forgery guard.
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()

            service.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    canonicalAuthor = canonicalAuthorDomain,
                    canonicalParticipants = listOf(
                        OdinId(canonicalAuthorDomain),
                        OdinId(fixture.testDomain),
                    ),
                    canonicalAdmins = listOf(OdinId(canonicalAuthorDomain)),
                ),
                sender = OdinId("malicious.test"), // ≠ canonicalAuthor
                messageFile = stubStatusMessageFile(fixture.chatDriveId),
            )

            // No placeholder was written — handler exited at the forgery guard.
            assertNull(
                fixture.getConversationFile(convoId),
                "forged sender must not produce any local writes",
            )
        }
    }

    // ---------- helpers ----------

    /** Build a [GroupHealRequested] [StatusMessageData] with the canonical fields. */
    private fun healStatus(
        conversationId: Uuid,
        canonicalAuthor: String,
        canonicalParticipants: List<OdinId>,
        canonicalAdmins: List<OdinId>?,
        canonicalVersionTag: Uuid? = null,
        canonicalAdminVersionTag: Uuid? = null,
    ): StatusMessageData = StatusMessageData(
        statusMessage = StatusMessage.GroupHealRequested,
        groupHeal = GroupHealInfo(
            conversationUniqueId = conversationId,
            canonicalOriginalAuthor = OdinId(canonicalAuthor),
            canonicalVersionTag = canonicalVersionTag,
            canonicalTitle = "Test Group",
            canonicalParticipants = canonicalParticipants,
            canonicalAdminFileUniqueId = Uuid.random(), // not used by the handler
            canonicalAdminVersionTag = canonicalAdminVersionTag,
            canonicalAdmins = canonicalAdmins,
        ),
    )

    /**
     * Build an in-memory stub for the [HomebaseFile] passed as `messageFile`
     * to the heal handler. The file is deliberately *not* inserted into the
     * test DB — `markHealApplied` short-circuits cleanly when the file is
     * absent (updateLocalTags returns silently when the row doesn't exist),
     * so tests can focus on the cleanup behaviour.
     */
    private fun stubStatusMessageFile(driveId: Uuid): HomebaseFile = HomebaseFile(
        fileId = Uuid.random(),
        driveId = driveId,
        serverFileIsEncrypted = false,
        fileState = FileState.Active,
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
                content = null,
                previewThumbnail = null,
                archivalStatus = null,
            ),
            localAppData = LocalAppMetadata(tags = emptyList()),
            created = UnixTimeUtc.now(),
            updated = UnixTimeUtc.ZeroTime,
            isEncrypted = false,
            senderOdinId = OdinId(canonicalAuthorDomain),
            originalAuthor = OdinId(canonicalAuthorDomain),
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

    private fun Outbox.isHardDeleteRequest(): Boolean = asHardDeleteRequestOrNull() != null

    private fun Outbox.asHardDeleteRequestOrNull(): DeleteLocalFilesByFileIdRequest? {
        if (this.uploadType != DriveOutboxUploader.DeleteFile) return null
        return try {
            OdinSystemSerializer.deserialize<DeleteLocalFilesByFileIdRequest>(this.json.decodeToString())
        } catch (_: Throwable) {
            null
        }
    }

    private fun readPlaceholderRecipients(file: HomebaseFile): List<String> {
        val raw = file.fileMetadata.appData.content ?: return emptyList()
        val content = OdinSystemSerializer.deserialize<ConversationAppDataJson>(raw)
        return content.recipients?.filterNotNull()?.map { it.domainName } ?: emptyList()
    }

    private fun readAdminPlaceholderAdmins(file: HomebaseFile): List<String> {
        val raw = file.fileMetadata.appData.content ?: return emptyList()
        val content = OdinSystemSerializer.deserialize<ConversationAdminInfo>(raw)
        return content.admins?.map { it.domainName } ?: emptyList()
    }
}
