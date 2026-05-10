package id.homebase.chat.groupsettings

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.LocalAppMetadata
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Pure-function tests for [toDbRow] / [toServerRow] — the projection
 * helpers that drive the four-row file-health diagnostic at the bottom of
 * the group-info screen.
 *
 * The projection rules:
 *
 *   DB-side:
 *     null               → Absent
 *     versionTag = null  → Placeholder (local-only marker)
 *     versionTag = X, author present → Present(X, author, fileId)
 *     versionTag = X, author null    → Placeholder (malformed; defensive)
 *
 *   Server-side (response from getFileHeaderByUid):
 *     null               → Absent
 *     versionTag = null  → Absent (server should never return this; logged)
 *     author null        → Absent (malformed; logged)
 *     versionTag = X, author present → Present(X, author, fileId)
 */
class GroupFilesDiagnosticProjectionTest {

    private val alice = OdinId("alice.test")

    @Test
    fun toDbRow_null_returnsAbsent() {
        assertEquals(DbFileRow.Absent, toDbRow(null))
    }

    @Test
    fun toDbRow_versionTagNull_returnsPlaceholderWithFileId() {
        val fileId = Uuid.random()
        val file = stubFile(versionTag = null, originalAuthor = alice, fileId = fileId)

        val row = toDbRow(file)

        assertIs<DbFileRow.Placeholder>(row)
        assertEquals(fileId, row.fileId)
    }

    @Test
    fun toDbRow_versionTagPresent_returnsPresentWithAuthorAndVersion() {
        val fileId = Uuid.random()
        val vt = Uuid.random()
        val file = stubFile(versionTag = vt, originalAuthor = alice, fileId = fileId)

        val row = toDbRow(file)

        assertIs<DbFileRow.Present>(row)
        assertEquals(vt, row.versionTag)
        assertEquals(alice, row.originalAuthor)
        assertEquals(fileId, row.fileId)
    }

    @Test
    fun toDbRow_originalAuthorNull_fallsBackToSenderOdinId() {
        // Forwarded / wire-only files can have a null originalAuthor; the
        // diagnostic should fall back to senderOdinId so we still render an
        // author column instead of pretending the row is malformed.
        val sender = OdinId("forwarder.test")
        val file = stubFile(
            versionTag = Uuid.random(),
            originalAuthor = null,
            senderOdinId = sender,
        )

        val row = toDbRow(file)

        assertIs<DbFileRow.Present>(row)
        assertEquals(sender, row.originalAuthor)
    }

    @Test
    fun toDbRow_bothAuthorsNullDespiteVersionTag_returnsPlaceholder() {
        // Defensive: a row with a real versionTag but no author at all is
        // malformed. Surface as Placeholder so the UI shows the oddity in
        // red rather than crashing on the unwrap.
        val file = stubFile(
            versionTag = Uuid.random(),
            originalAuthor = null,
            senderOdinId = null,
        )

        val row = toDbRow(file)

        assertIs<DbFileRow.Placeholder>(row)
    }

    @Test
    fun toServerRow_null_isAbsent() {
        assertEquals(ServerFileRow.Absent, toServerRow(null))
    }

    @Test
    fun toServerRow_serverFileWithVersionTagNull_isAbsent() {
        // Server never stores placeholders. If the API ever returns a file
        // with versionTag=null, treat it as Absent — but we log so we'd see
        // the regression in homebase.log.
        val file = stubFile(versionTag = null, originalAuthor = alice)

        assertEquals(ServerFileRow.Absent, toServerRow(file))
    }

    @Test
    fun toServerRow_serverFileWithNoAuthor_isAbsent() {
        // Defensive: server file with versionTag but no author at all.
        val file = stubFile(
            versionTag = Uuid.random(),
            originalAuthor = null,
            senderOdinId = null,
        )

        assertEquals(ServerFileRow.Absent, toServerRow(file))
    }

    @Test
    fun toServerRow_present_carriesVersionTagAuthorAndFileId() {
        val fileId = Uuid.random()
        val vt = Uuid.random()
        val file = stubFile(versionTag = vt, originalAuthor = alice, fileId = fileId)

        val row = toServerRow(file)

        assertIs<ServerFileRow.Present>(row)
        assertEquals(vt, row.versionTag)
        assertEquals(alice, row.originalAuthor)
        assertEquals(fileId, row.fileId)
    }

    @Test
    fun supportBlob_includesAllFourRowsAndExpectedUids() {
        val convoId = Uuid.random()
        val expectedAdminUid = Uuid.random()
        val mainVt = Uuid.random()
        val mainFileId = Uuid.random()
        val adminPlaceholderFileId = Uuid.random()

        val diagnostic = GroupFilesDiagnostic(
            conversationId = convoId,
            expectedMainUniqueId = convoId,
            expectedAdminUniqueId = expectedAdminUid,
            dbGroup = DbFileRow.Present(versionTag = mainVt, originalAuthor = alice, fileId = mainFileId),
            dbAdmin = DbFileRow.Placeholder(fileId = adminPlaceholderFileId),
            serverGroup = ServerFileRow.Present(versionTag = mainVt, originalAuthor = alice, fileId = mainFileId),
            serverAdmin = ServerFileRow.Absent,
        )

        val blob = buildSupportBlob(diagnostic, selfDomain = "owner.test")

        // Sanity: every row label appears, full UUIDs (not 8-char prefix)
        // are present so support can grep by them.
        assertTrue(blob.contains("convo=$convoId"), "support blob must include the conversation id")
        assertTrue(blob.contains("domain=owner.test"), "support blob must include the user's own domain")
        assertTrue(blob.contains("db-group=Present"), "db-group row missing in: $blob")
        assertTrue(blob.contains("db-admin=Placeholder"), "db-admin row missing in: $blob")
        assertTrue(blob.contains("server-group=Present"), "server-group row missing in: $blob")
        assertTrue(blob.contains("server-admin=Absent"), "server-admin row missing in: $blob")
        assertTrue(blob.contains("vt=$mainVt"), "support blob must carry the FULL versionTag for grep, got: $blob")
        assertTrue(blob.contains("expectedAdminUid=$expectedAdminUid"), "must carry expected admin uid")
    }

    // ---------- helpers ----------

    private fun stubFile(
        versionTag: Uuid?,
        originalAuthor: OdinId?,
        senderOdinId: OdinId? = originalAuthor,
        fileId: Uuid = Uuid.random(),
    ): HomebaseFile = HomebaseFile(
        fileId = fileId,
        driveId = Uuid.random(),
        serverFileIsEncrypted = false,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader.newRandom16(),
        fileMetadata = FileMetadata(
            appData = AppFileMetaData(
                uniqueId = Uuid.random(),
                tags = null,
                fileType = 8888,
                dataType = 0,
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
            senderOdinId = senderOdinId,
            originalAuthor = originalAuthor,
            versionTag = versionTag,
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
}
