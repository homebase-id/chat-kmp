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
 * Tier-3 coverage for [GroupHealService.handleIncomingHealRequest].
 *
 * The handler is the receive-side of the group-heal protocol. It runs when a
 * [StatusMessage.GroupHealRequested] status message arrives from the canonical
 * author of a group. The handler must:
 *
 *   1. Detect both **broken** (file present but mismatched author) and
 *      **missing** (no local row at all) states. A previous implementation
 *      collapsed missing into "no-op" because mainFile==null short-circuited
 *      the && isServerFileBroken check, leaving recipients permanently stuck.
 *   2. Hard-delete *only* the broken local files (a missing file has no
 *      fileId to act on and the server already lacks the row).
 *   3. Write a self-sufficient local placeholder (versionTag=null) for both
 *      the main and admin files so the UI behaves as if nothing was broken
 *      while the canonical author's peer push is in flight.
 *   4. Skip the admin placeholder when the heal payload omits
 *      [GroupHealInfo.canonicalAdmins] (legacy senders) — fall back to the
 *      delete-only path so getAdmins()' originalAuthor fallback keeps things
 *      functional.
 */
class ConversationServiceHealTest {

    private val canonicalAuthorDomain = "alice.test"

    @Test
    fun mainMissing_writesPlaceholderFromCanonicalParticipants_andDoesNotEnqueueHardDelete() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val healService = fixture.buildHealService(service)
            val convoId = Uuid.random()
            val canonicalParticipants = listOf(
                OdinId(canonicalAuthorDomain),
                OdinId(fixture.testDomain),
                OdinId("bob.test"),
            )
            val outboxRowsBefore = fixture.outboxRowCount()
            val healMsg = stubStatusMessageFile(fixture.chatDriveId)

            healService.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    canonicalAuthor = canonicalAuthorDomain,
                    canonicalParticipants = canonicalParticipants,
                    canonicalAdmins = listOf(OdinId(canonicalAuthorDomain)),
                ),
                sender = OdinId(canonicalAuthorDomain),
                messageFile = healMsg,
            )

            // Missing file ⇒ no hard-delete for the *group* file. The only
            // hard-delete we expect is the self-destruct of the heal message
            // itself.
            val drained = fixture.drainOutbox()
            val hardDeletes = drained.mapNotNull { it.asHardDeleteRequestOrNull() }
            val nonSelfDestructHardDeletes = hardDeletes.filter { req ->
                req.fileIds.any { it != healMsg.fileId }
            }
            assertTrue(
                nonSelfDestructHardDeletes.isEmpty(),
                "missing file ⇒ NO hard-delete for the broken group file should be enqueued; got $nonSelfDestructHardDeletes",
            )
            assertTrue(
                hardDeletes.any { it.fileIds.contains(healMsg.fileId) },
                "heal message must self-destruct (hard-delete enqueued for its fileId=${healMsg.fileId}); got $hardDeletes",
            )

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
    fun healMessage_selfDestructs_localRowIsHardDeletedAfterHandling() = runTest {
        // Seeds the heal status message itself into the local DB, runs the
        // handler, and confirms the row is gone from DriveMainIndex. This is
        // the load-bearing piece of "runs exactly once": once the row is gone
        // locally, the upstream dispatcher (which iterates raw HomebaseFiles)
        // can't surface it again on a future BatchReceived — there's nothing
        // to surface.
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val healService = fixture.buildHealService(service)
            val convoId = Uuid.random()
            // No-op heal path is fine — we only care that the message itself
            // gets erased. Seed a healthy group so cleanup is a no-op.
            fixture.seedGroup(
                conversationId = convoId,
                others = listOf(canonicalAuthorDomain),
                adminDomains = listOf(canonicalAuthorDomain),
                seedAdminFile = false,
            )
            val healMsg = stubStatusMessageFile(fixture.chatDriveId)
            fixture.insertHomebaseFile(healMsg)
            val healMsgUniqueId = healMsg.fileMetadata.appData.uniqueId
            assertNotNull(healMsgUniqueId, "precondition: stub has a uniqueId")
            // Precondition: the heal message is in the DB before we process it.
            assertNotNull(
                fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                    fixture.testIdentityId, fixture.chatDriveId, healMsgUniqueId,
                ),
                "precondition: heal message must be in the DB before handling",
            )

            healService.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    canonicalAuthor = canonicalAuthorDomain,
                    canonicalParticipants = listOf(
                        OdinId(canonicalAuthorDomain),
                        OdinId(fixture.testDomain),
                    ),
                    canonicalAdmins = listOf(OdinId(canonicalAuthorDomain)),
                ),
                sender = OdinId(canonicalAuthorDomain),
                messageFile = healMsg,
            )

            // Local hard-delete fired: the row is gone from DriveMainIndex,
            // so the dispatcher's raw-file iteration can't see it on a
            // future BatchReceived.
            assertNull(
                fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                    fixture.testIdentityId, fixture.chatDriveId, healMsgUniqueId,
                ),
                "heal message must self-destruct: row should be gone from DriveMainIndex after handling",
            )
        }
    }

    @Test
    fun mainBroken_hardDeletesLocalFile_andWritesPlaceholderFromCanonicalIdentity() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val healService = fixture.buildHealService(service)
            val convoId = Uuid.random()
            // Seed a "broken" main file: originalAuthor = us, but the canonical
            // author claims to be alice. isServerFileBroken returns true (author mismatch).
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
            val healMsg = stubStatusMessageFile(fixture.chatDriveId)

            healService.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    canonicalAuthor = canonicalAuthorDomain,
                    canonicalParticipants = canonicalParticipants,
                    canonicalAdmins = listOf(OdinId(canonicalAuthorDomain)),
                ),
                sender = OdinId(canonicalAuthorDomain),
                messageFile = healMsg,
            )

            // Hard-delete request enqueued targeting the broken main fileId,
            // AND the heal message must self-destruct.
            val drained = fixture.drainOutbox()
            val deletes = drained.mapNotNull { it.asHardDeleteRequestOrNull() }
            assertTrue(
                deletes.any { req -> req.fileIds.contains(brokenMainFileId) && req.hardDelete },
                "broken main file ⇒ hardDelete=true request for fileId=$brokenMainFileId; got $deletes",
            )
            assertTrue(
                deletes.any { req -> req.fileIds.contains(healMsg.fileId) && req.hardDelete },
                "heal message must self-destruct (hard-delete enqueued for its fileId=${healMsg.fileId}); got $deletes",
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
            val healService = fixture.buildHealService(service)
            val convoId = Uuid.random()
            val canonicalAdmins = listOf(
                OdinId(canonicalAuthorDomain),
                OdinId("bob.test"),
            )
            // Leave both files missing; both branches fire.
            val healMsg = stubStatusMessageFile(fixture.chatDriveId)

            healService.handleIncomingHealRequest(
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
                messageFile = healMsg,
            )

            // Heal message must self-destruct.
            val drained = fixture.drainOutbox()
            val deletes = drained.mapNotNull { it.asHardDeleteRequestOrNull() }
            assertTrue(
                deletes.any { req -> req.fileIds.contains(healMsg.fileId) && req.hardDelete },
                "heal message must self-destruct (hard-delete enqueued for its fileId=${healMsg.fileId}); got $deletes",
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
            val healService = fixture.buildHealService(service)
            val convoId = Uuid.random()
            // Both main and admin missing; legacy payload (canonicalAdmins=null).
            val healMsg = stubStatusMessageFile(fixture.chatDriveId)

            healService.handleIncomingHealRequest(
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
                messageFile = healMsg,
            )

            // Heal message must self-destruct.
            val drained = fixture.drainOutbox()
            val deletes = drained.mapNotNull { it.asHardDeleteRequestOrNull() }
            assertTrue(
                deletes.any { req -> req.fileIds.contains(healMsg.fileId) && req.hardDelete },
                "heal message must self-destruct (hard-delete enqueued for its fileId=${healMsg.fileId}); got $deletes",
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
    fun selfLoopback_skipsHandling() = runTest {
        // Sender is us — we're seeing our own outgoing heal status loop back.
        // The handler must short-circuit before any cleanup work.
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val healService = fixture.buildHealService(service)
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
            val healMsg = stubStatusMessageFile(fixture.chatDriveId)

            healService.handleIncomingHealRequest(
                status = healStatus(
                    conversationId = convoId,
                    // Canonical author is us → self-loopback.
                    canonicalAuthor = fixture.testDomain,
                    canonicalParticipants = listOf(OdinId(fixture.testDomain), OdinId("bob.test")),
                    canonicalAdmins = listOf(OdinId(fixture.testDomain)),
                ),
                sender = OdinId(fixture.testDomain),
                messageFile = healMsg,
            )

            // File untouched.
            assertEquals(seededFileId, fixture.getConversationFile(convoId)?.fileId)
            // No outbox enqueue — including no self-destruct of the heal message.
            val drained = fixture.drainOutbox()
            val deletes = drained.mapNotNull { it.asHardDeleteRequestOrNull() }
            assertTrue(deletes.isEmpty(), "self-loopback must NOT enqueue any hard-delete")
            assertTrue(
                deletes.none { it.fileIds.contains(healMsg.fileId) },
                "self-loopback must NOT self-destruct the heal message — message stays as diagnostic evidence",
            )
        }
    }

    @Test
    fun forgedSender_skipsHandling() = runTest {
        // Sender's domain doesn't match the canonical author — forgery guard.
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val healService = fixture.buildHealService(service)
            val convoId = Uuid.random()

            val healMsg = stubStatusMessageFile(fixture.chatDriveId)
            healService.handleIncomingHealRequest(
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
                messageFile = healMsg,
            )

            // No placeholder was written — handler exited at the forgery guard.
            assertNull(
                fixture.getConversationFile(convoId),
                "forged sender must not produce any local writes",
            )
            // And the forged heal message stays in the chat as diagnostic
            // evidence — no self-destruct on the forgery early-return path.
            val drained = fixture.drainOutbox()
            val deletes = drained.mapNotNull { it.asHardDeleteRequestOrNull() }
            assertTrue(
                deletes.none { it.fileIds.contains(healMsg.fileId) },
                "forged sender must NOT self-destruct the heal message",
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
     * test DB — the self-destruct path (optimisticWriter.writeDelete +
     * dbm.driveMainIndex.deleteBy) short-circuits cleanly when the row is
     * absent, so tests can focus on the cleanup behaviour.
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
        return content.recipients.filterNotNull().map { it.domainName }
    }

    private fun readAdminPlaceholderAdmins(file: HomebaseFile): List<String> {
        val raw = file.fileMetadata.appData.content ?: return emptyList()
        val content = OdinSystemSerializer.deserialize<ConversationAdminInfo>(raw)
        return content.admins?.map { it.domainName } ?: emptyList()
    }
}
