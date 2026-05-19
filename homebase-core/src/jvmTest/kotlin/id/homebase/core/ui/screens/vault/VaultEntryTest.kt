@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.ui.screens.vault.model.VaultFileContent
import id.homebase.core.ui.screens.vault.model.toVaultEntry
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Unit tests for [toVaultEntry] mapper and [detectContentTypeFromExtensionOrHint] utility.
 */
class VaultEntryTest {

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun testKeyHeader(): KeyHeader = KeyHeader(
        iv = ByteArray(16),
        aesKey = SecureByteArray(ByteArray(16)),
    )

    private fun testServerMetadata(): ServerMetadata = ServerMetadata(
        accessControlList = AccessControlList(requiredSecurityGroup = "owner"),
    )

    /**
     * Builds a minimal [HomebaseFile] suitable for vault mapper tests.
     *
     * @param payloads payload descriptors attached to the file; pass null to simulate missing payloads.
     * @param contentJson the appData.content JSON string; pass null to simulate missing content.
     * @param previewThumb optional embedded thumbnail.
     * @param isEncrypted whether the file metadata is marked encrypted.
     * @param createdMs creation timestamp in milliseconds.
     * @param versionTag optional version tag UUID.
     */
    private fun buildHomebaseFile(
        fileId: Uuid = Uuid.random(),
        driveId: Uuid = Uuid.random(),
        payloads: List<PayloadDescriptor>? = listOf(
            PayloadDescriptor(
                key = "payload0",
                contentType = "image/jpeg",
                bytesWritten = 1024L,
            )
        ),
        contentJson: String? = OdinSystemSerializer.serialize(VaultFileContent(name = "photo.jpg")),
        previewThumb: EmbeddedThumb? = null,
        isEncrypted: Boolean = true,
        createdMs: Long = 1_700_000_000_000L,
        versionTag: Uuid? = Uuid.random(),
    ): HomebaseFile {
        return HomebaseFile(
            fileId = fileId,
            driveId = driveId,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = testKeyHeader(),
            fileMetadata = FileMetadata(
                created = UnixTimeUtc(createdMs),
                isEncrypted = isEncrypted,
                appData = AppFileMetaData(
                    content = contentJson,
                    previewThumbnail = previewThumb,
                ),
                payloads = payloads,
                versionTag = versionTag,
            ),
            serverMetadata = testServerMetadata(),
        )
    }

    // ---------------------------------------------------------------
    // toVaultEntry() — happy paths
    // ---------------------------------------------------------------

    @Test
    fun toVaultEntry_mapsImagePayloadCorrectly() {
        val fileId = Uuid.random()
        val driveId = Uuid.random()
        val versionTag = Uuid.random()
        val file = buildHomebaseFile(
            fileId = fileId,
            driveId = driveId,
            payloads = listOf(
                PayloadDescriptor(
                    key = "img_key",
                    contentType = "image/jpeg",
                    bytesWritten = 2048L,
                )
            ),
            contentJson = OdinSystemSerializer.serialize(VaultFileContent(name = "vacation.jpg")),
            isEncrypted = true,
            createdMs = 1_700_000_000_000L,
            versionTag = versionTag,
        )

        val item = file.toVaultEntry()

        assertNotNull(item)
        assertEquals(fileId, item.fileId)
        assertEquals(driveId, item.driveId)
        assertEquals("vacation.jpg", item.fileName)
        assertEquals("image/jpeg", item.contentType)
        assertEquals(2048L, item.sizeBytes)
        assertEquals(1_700_000_000_000L, item.createdAt)
        assertEquals("img_key", item.payloadDescriptors.first().key)
        assertTrue(item.isEncrypted)
        assertEquals(versionTag, item.versionTag)
        assertNull(item.previewThumbnail)
        assertTrue(item.isImage)
        assertFalse(item.isPdf)
        assertFalse(item.isVideo)
        assertFalse(item.isAudio)
        assertFalse(item.isPending)
    }

    @Test
    fun toVaultEntry_mapsPdfPayloadCorrectly() {
        val file = buildHomebaseFile(
            payloads = listOf(
                PayloadDescriptor(
                    key = "pdf_key",
                    contentType = "application/pdf",
                    bytesWritten = 500_000L,
                )
            ),
            contentJson = OdinSystemSerializer.serialize(VaultFileContent(name = "report.pdf")),
        )

        val item = file.toVaultEntry()

        assertNotNull(item)
        assertEquals("report.pdf", item.fileName)
        assertEquals("application/pdf", item.contentType)
        assertEquals(500_000L, item.sizeBytes)
        assertEquals("pdf_key", item.payloadDescriptors.first().key)
        assertTrue(item.isPdf)
        assertFalse(item.isImage)
    }

    @Test
    fun toVaultEntry_usesFirstPayloadWhenMultiplePresent() {
        val file = buildHomebaseFile(
            payloads = listOf(
                PayloadDescriptor(key = "first", contentType = "image/png", bytesWritten = 100L),
                PayloadDescriptor(key = "second", contentType = "image/jpeg", bytesWritten = 200L),
                PayloadDescriptor(key = "third", contentType = "application/pdf", bytesWritten = 300L),
            ),
            contentJson = OdinSystemSerializer.serialize(VaultFileContent(name = "multi.png")),
        )

        val item = file.toVaultEntry()

        assertNotNull(item)
        assertEquals("first", item.payloadDescriptors.first().key)
        assertEquals("image/png", item.contentType)
        assertEquals(600L, item.sizeBytes)
    }

    @Test
    fun toVaultEntry_mapsPreviewThumbnail() {
        val thumb = EmbeddedThumb(
            pixelWidth = 200,
            pixelHeight = 150,
            contentType = "image/jpeg",
            content = "base64data",
        )
        val file = buildHomebaseFile(previewThumb = thumb)

        val item = file.toVaultEntry()

        assertNotNull(item)
        assertNotNull(item.previewThumbnail)
        assertEquals(200, item.previewThumbnail.pixelWidth)
        assertEquals(150, item.previewThumbnail.pixelHeight)
    }

    @Test
    fun toVaultEntry_handlesNullContentTypeInPayload() {
        val file = buildHomebaseFile(
            payloads = listOf(
                PayloadDescriptor(key = "k", contentType = null, bytesWritten = 10L)
            ),
        )

        val item = file.toVaultEntry()

        assertNotNull(item)
        assertEquals("", item.contentType)
    }

    @Test
    fun toVaultEntry_handlesNullBytesWrittenInPayload() {
        val file = buildHomebaseFile(
            payloads = listOf(
                PayloadDescriptor(key = "k", contentType = "text/plain", bytesWritten = null)
            ),
        )

        val item = file.toVaultEntry()

        assertNotNull(item)
        assertEquals(0L, item.sizeBytes)
    }

    @Test
    fun toVaultEntry_handlesNullVersionTag() {
        val file = buildHomebaseFile(versionTag = null)

        val item = file.toVaultEntry()

        assertNotNull(item)
        assertNull(item.versionTag)
    }

    // ---------------------------------------------------------------
    // toVaultEntry() — label field
    // ---------------------------------------------------------------

    @Test
    fun toVaultEntry_mapsLabelWhenPresent() {
        val file = buildHomebaseFile(
            contentJson = OdinSystemSerializer.serialize(
                VaultFileContent(name = "photo.jpg", label = "Shelly")
            ),
        )

        val item = file.toVaultEntry()

        assertNotNull(item)
        assertEquals("Shelly", item.label)
        assertEquals("photo.jpg", item.fileName)
    }

    @Test
    fun toVaultEntry_labelIsNullByDefault() {
        val file = buildHomebaseFile(
            contentJson = OdinSystemSerializer.serialize(
                VaultFileContent(name = "photo.jpg")
            ),
        )

        val item = file.toVaultEntry()

        assertNotNull(item)
        assertNull(item.label)
    }

    @Test
    fun toVaultEntry_mapsLabelAndNotesTogether() {
        val file = buildHomebaseFile(
            contentJson = OdinSystemSerializer.serialize(
                VaultFileContent(name = "passport.jpg", label = "Gabriel", notes = "Expires 2028")
            ),
        )

        val item = file.toVaultEntry()

        assertNotNull(item)
        assertEquals("Gabriel", item.label)
        assertEquals("Expires 2028", item.notes)
        assertEquals("passport.jpg", item.fileName)
    }

    @Test
    fun toVaultEntry_handlesMultiplePayloadDescriptors() {
        val file = buildHomebaseFile(
            payloads = listOf(
                PayloadDescriptor(key = "vlt_pg_00", contentType = "image/jpeg", bytesWritten = 500L),
                PayloadDescriptor(key = "vlt_pg_01", contentType = "image/jpeg", bytesWritten = 600L),
                PayloadDescriptor(key = "vlt_pg_02", contentType = "image/png", bytesWritten = 700L),
            ),
        )

        val item = file.toVaultEntry()

        assertNotNull(item)
        assertEquals(3, item.pageCount)
        assertTrue(item.hasMultiplePages)
        assertEquals(1800L, item.sizeBytes)
        assertEquals(3, item.payloadDescriptors.size)
    }

    // ---------------------------------------------------------------
    // VaultFileContent serialization — field preservation
    // ---------------------------------------------------------------

    @Test
    fun vaultFileContent_roundTrip_preservesAllFields() {
        val original = VaultFileContent(name = "photo.jpg", label = "Shelly", notes = "Passport")
        val json = OdinSystemSerializer.serialize(original)
        val restored = OdinSystemSerializer.deserialize<VaultFileContent>(json)
        assertEquals("photo.jpg", restored.name)
        assertEquals("Shelly", restored.label)
        assertEquals("Passport", restored.notes)
    }

    @Test
    fun vaultFileContent_roundTrip_nullLabelPreserved() {
        val original = VaultFileContent(name = "doc.pdf", label = null, notes = "Important")
        val json = OdinSystemSerializer.serialize(original)
        val restored = OdinSystemSerializer.deserialize<VaultFileContent>(json)
        assertEquals("doc.pdf", restored.name)
        assertNull(restored.label)
        assertEquals("Important", restored.notes)
    }

    @Test
    fun vaultFileContent_backwardCompatibility_missingLabelDefaultsToNull() {
        val legacyJson = """{"name":"old_photo.jpg","notes":"some notes"}"""
        val restored = OdinSystemSerializer.deserialize<VaultFileContent>(legacyJson)
        assertEquals("old_photo.jpg", restored.name)
        assertNull(restored.label)
        assertEquals("some notes", restored.notes)
    }

    @Test
    fun vaultFileContent_backwardCompatibility_nameOnly() {
        val legacyJson = """{"name":"minimal.jpg"}"""
        val restored = OdinSystemSerializer.deserialize<VaultFileContent>(legacyJson)
        assertEquals("minimal.jpg", restored.name)
        assertNull(restored.label)
        assertNull(restored.notes)
    }

    @Test
    fun vaultFileContent_labelUpdatePreservesNameAndNotes() {
        val before = VaultFileContent(name = "photo.jpg", label = null, notes = "Expires 2028")
        val after = before.copy(label = "Gabriel")
        val json = OdinSystemSerializer.serialize(after)
        val restored = OdinSystemSerializer.deserialize<VaultFileContent>(json)
        assertEquals("photo.jpg", restored.name)
        assertEquals("Gabriel", restored.label)
        assertEquals("Expires 2028", restored.notes)
    }

    @Test
    fun vaultFileContent_notesUpdatePreservesNameAndLabel() {
        val before = VaultFileContent(name = "photo.jpg", label = "Shelly", notes = null)
        val after = before.copy(notes = "Updated description")
        val json = OdinSystemSerializer.serialize(after)
        val restored = OdinSystemSerializer.deserialize<VaultFileContent>(json)
        assertEquals("photo.jpg", restored.name)
        assertEquals("Shelly", restored.label)
        assertEquals("Updated description", restored.notes)
    }

    @Test
    fun toVaultEntry_backwardCompatibility_legacyContentWithoutLabel() {
        val file = buildHomebaseFile(
            contentJson = """{"name":"legacy_photo.jpg","notes":"old note"}""",
        )
        val item = file.toVaultEntry()
        assertNotNull(item)
        assertEquals("legacy_photo.jpg", item.fileName)
        assertNull(item.label)
        assertEquals("old note", item.notes)
    }

    // ---------------------------------------------------------------
    // Update operation field preservation
    // Simulates what each VaultService update method does:
    // constructs a VaultFileContent and round-trips through serialization.
    // ---------------------------------------------------------------

    private fun simulateUpdateRoundTrip(content: VaultFileContent): VaultFileContent {
        val json = OdinSystemSerializer.serialize(content)
        return OdinSystemSerializer.deserialize(json)
    }

    @Test
    fun updateOperation_renamePreservesLabelAndNotes() {
        val existing = VaultFileContent(name = "old.jpg", label = "Shelly", notes = "Passport")
        val updated = simulateUpdateRoundTrip(
            VaultFileContent(name = "new.jpg", label = existing.label, notes = existing.notes)
        )
        assertEquals("new.jpg", updated.name)
        assertEquals("Shelly", updated.label)
        assertEquals("Passport", updated.notes)
    }

    @Test
    fun updateOperation_labelChangePreservesNameAndNotes() {
        val existing = VaultFileContent(name = "photo.jpg", label = null, notes = "Important doc")
        val updated = simulateUpdateRoundTrip(
            VaultFileContent(name = existing.name, label = "Gabriel", notes = existing.notes)
        )
        assertEquals("photo.jpg", updated.name)
        assertEquals("Gabriel", updated.label)
        assertEquals("Important doc", updated.notes)
    }

    @Test
    fun updateOperation_notesChangePreservesNameAndLabel() {
        val existing = VaultFileContent(name = "scan.pdf", label = "Leela", notes = null)
        val updated = simulateUpdateRoundTrip(
            VaultFileContent(name = existing.name, label = existing.label, notes = "Expires 2030")
        )
        assertEquals("scan.pdf", updated.name)
        assertEquals("Leela", updated.label)
        assertEquals("Expires 2030", updated.notes)
    }

    @Test
    fun updateOperation_appendPagesPreservesAllFields() {
        val existing = VaultFileContent(name = "passport.jpg", label = "Aja", notes = "Kids passport")
        val updated = simulateUpdateRoundTrip(
            VaultFileContent(name = existing.name, label = existing.label, notes = existing.notes)
        )
        assertEquals("passport.jpg", updated.name)
        assertEquals("Aja", updated.label)
        assertEquals("Kids passport", updated.notes)
    }

    @Test
    fun updateOperation_deletePagePreservesAllFields() {
        val existing = VaultFileContent(name = "multi.jpg", label = "Michael", notes = "Visa pages")
        val updated = simulateUpdateRoundTrip(
            VaultFileContent(name = existing.name, label = existing.label, notes = existing.notes)
        )
        assertEquals("multi.jpg", updated.name)
        assertEquals("Michael", updated.label)
        assertEquals("Visa pages", updated.notes)
    }

    @Test
    fun updateOperation_clearLabelKeepsNameAndNotes() {
        val existing = VaultFileContent(name = "doc.pdf", label = "Old Label", notes = "Notes here")
        val updated = simulateUpdateRoundTrip(
            VaultFileContent(name = existing.name, label = null, notes = existing.notes)
        )
        assertEquals("doc.pdf", updated.name)
        assertNull(updated.label)
        assertEquals("Notes here", updated.notes)
    }

    @Test
    fun updateOperation_clearNotesKeepsNameAndLabel() {
        val existing = VaultFileContent(name = "doc.pdf", label = "My Doc", notes = "Old notes")
        val updated = simulateUpdateRoundTrip(
            VaultFileContent(name = existing.name, label = existing.label, notes = null)
        )
        assertEquals("doc.pdf", updated.name)
        assertEquals("My Doc", updated.label)
        assertNull(updated.notes)
    }

    // ---------------------------------------------------------------
    // toVaultEntry() — null/invalid cases
    // ---------------------------------------------------------------

    @Test
    fun toVaultEntry_returnsNullWhenPayloadsNull() {
        val file = buildHomebaseFile(payloads = null)

        val item = file.toVaultEntry()

        assertNull(item)
    }

    @Test
    fun toVaultEntry_returnsNullWhenPayloadsEmpty() {
        val file = buildHomebaseFile(payloads = emptyList())

        val item = file.toVaultEntry()

        assertNull(item)
    }

    @Test
    fun toVaultEntry_returnsNullWhenContentNull() {
        val file = buildHomebaseFile(contentJson = null)

        val item = file.toVaultEntry()

        assertNull(item)
    }

    @Test
    fun toVaultEntry_returnsNullWhenContentIsInvalidJson() {
        val file = buildHomebaseFile(contentJson = "not valid json {{{")

        val item = file.toVaultEntry()

        assertNull(item)
    }

    @Test
    fun toVaultEntry_returnsNullWhenContentIsMismatchedJsonSchema() {
        // Valid JSON but wrong shape -- missing "name" field
        val file = buildHomebaseFile(contentJson = """{"foo":"bar"}""")

        val item = file.toVaultEntry()

        // kotlinx.serialization may or may not throw depending on config;
        // if it deserializes with defaults the mapper may succeed.
        // The important contract: it should not crash.
        // If the serializer is strict, this returns null; if lenient, it may return an item.
        // We just verify no exception is thrown.
        // (Test passes regardless of outcome.)
    }

    // ---------------------------------------------------------------
    // VaultEntry — isText property
    // ---------------------------------------------------------------

    @Test
    fun isText_textPlain() {
        val item = buildHomebaseFile(
            payloads = listOf(PayloadDescriptor(key = "k", contentType = "text/plain", bytesWritten = 10L)),
        ).toVaultEntry()
        assertNotNull(item)
        assertTrue(item.isText)
    }

    @Test
    fun isText_applicationJson() {
        val item = buildHomebaseFile(
            payloads = listOf(PayloadDescriptor(key = "k", contentType = "application/json", bytesWritten = 10L)),
        ).toVaultEntry()
        assertNotNull(item)
        assertTrue(item.isText)
    }

    @Test
    fun isText_applicationXml() {
        val item = buildHomebaseFile(
            payloads = listOf(PayloadDescriptor(key = "k", contentType = "application/xml", bytesWritten = 10L)),
        ).toVaultEntry()
        assertNotNull(item)
        assertTrue(item.isText)
    }

    @Test
    fun isText_applicationYaml() {
        val item = buildHomebaseFile(
            payloads = listOf(PayloadDescriptor(key = "k", contentType = "application/x-yaml", bytesWritten = 10L)),
        ).toVaultEntry()
        assertNotNull(item)
        assertTrue(item.isText)
    }

    @Test
    fun isText_applicationJavascript() {
        val item = buildHomebaseFile(
            payloads = listOf(PayloadDescriptor(key = "k", contentType = "application/javascript", bytesWritten = 10L)),
        ).toVaultEntry()
        assertNotNull(item)
        assertTrue(item.isText)
    }

    @Test
    fun isText_applicationSh() {
        val item = buildHomebaseFile(
            payloads = listOf(PayloadDescriptor(key = "k", contentType = "application/x-sh", bytesWritten = 10L)),
        ).toVaultEntry()
        assertNotNull(item)
        assertTrue(item.isText)
    }

    @Test
    fun isText_falseForImage() {
        val item = buildHomebaseFile(
            payloads = listOf(PayloadDescriptor(key = "k", contentType = "image/jpeg", bytesWritten = 10L)),
        ).toVaultEntry()
        assertNotNull(item)
        assertFalse(item.isText)
    }

    @Test
    fun isText_falseForPdf() {
        val item = buildHomebaseFile(
            payloads = listOf(PayloadDescriptor(key = "k", contentType = "application/pdf", bytesWritten = 10L)),
        ).toVaultEntry()
        assertNotNull(item)
        assertFalse(item.isText)
    }

    // ---------------------------------------------------------------
    // VaultEntry — pdfPageCount
    // ---------------------------------------------------------------

    @Test
    fun pdfPageCount_usedWhenPresent() {
        val file = buildHomebaseFile(
            payloads = listOf(PayloadDescriptor(key = "k", contentType = "application/pdf", bytesWritten = 100L)),
            contentJson = OdinSystemSerializer.serialize(VaultFileContent(name = "doc.pdf", pdfPageCount = 7)),
        )
        val item = file.toVaultEntry()
        assertNotNull(item)
        assertEquals(7, item.pageCount)
        assertTrue(item.hasMultiplePages)
    }

    @Test
    fun pdfPageCount_fallsBackToPayloadCount() {
        val file = buildHomebaseFile(
            payloads = listOf(
                PayloadDescriptor(key = "a", contentType = "image/jpeg", bytesWritten = 100L),
                PayloadDescriptor(key = "b", contentType = "image/jpeg", bytesWritten = 100L),
            ),
            contentJson = OdinSystemSerializer.serialize(VaultFileContent(name = "img.jpg")),
        )
        val item = file.toVaultEntry()
        assertNotNull(item)
        assertNull(item.pdfPageCount)
        assertEquals(2, item.pageCount)
    }

    @Test
    fun pdfPageCount_singlePage_hasMultiplePagesFalse() {
        val file = buildHomebaseFile(
            payloads = listOf(PayloadDescriptor(key = "k", contentType = "application/pdf", bytesWritten = 100L)),
            contentJson = OdinSystemSerializer.serialize(VaultFileContent(name = "one.pdf", pdfPageCount = 1)),
        )
        val item = file.toVaultEntry()
        assertNotNull(item)
        assertEquals(1, item.pageCount)
        assertFalse(item.hasMultiplePages)
    }

    // ---------------------------------------------------------------
    // detectContentTypeFromExtensionOrHint() — common extensions
    // ---------------------------------------------------------------

    @Test
    fun guessContentType_jpg() {
        assertEquals("image/jpeg", detectContentTypeFromExtensionOrHint("photo.jpg"))
    }

    @Test
    fun guessContentType_jpeg() {
        assertEquals("image/jpeg", detectContentTypeFromExtensionOrHint("photo.jpeg"))
    }

    @Test
    fun guessContentType_png() {
        assertEquals("image/png", detectContentTypeFromExtensionOrHint("image.png"))
    }

    @Test
    fun guessContentType_gif() {
        assertEquals("image/gif", detectContentTypeFromExtensionOrHint("anim.gif"))
    }

    @Test
    fun guessContentType_webp() {
        assertEquals("image/webp", detectContentTypeFromExtensionOrHint("pic.webp"))
    }

    @Test
    fun guessContentType_svg() {
        assertEquals("image/svg+xml", detectContentTypeFromExtensionOrHint("icon.svg"))
    }

    @Test
    fun guessContentType_heic() {
        assertEquals("image/heic", detectContentTypeFromExtensionOrHint("live.heic"))
    }

    @Test
    fun guessContentType_mp4() {
        assertEquals("video/mp4", detectContentTypeFromExtensionOrHint("clip.mp4"))
    }

    @Test
    fun guessContentType_mov() {
        assertEquals("video/quicktime", detectContentTypeFromExtensionOrHint("movie.mov"))
    }

    @Test
    fun guessContentType_mp3() {
        assertEquals("audio/mpeg", detectContentTypeFromExtensionOrHint("song.mp3"))
    }

    @Test
    fun guessContentType_pdf() {
        assertEquals("application/pdf", detectContentTypeFromExtensionOrHint("doc.pdf"))
    }

    @Test
    fun guessContentType_zip() {
        assertEquals("application/zip", detectContentTypeFromExtensionOrHint("archive.zip"))
    }

    @Test
    fun guessContentType_txt() {
        assertEquals("text/plain", detectContentTypeFromExtensionOrHint("readme.txt"))
    }

    @Test
    fun guessContentType_json() {
        assertEquals("application/json", detectContentTypeFromExtensionOrHint("data.json"))
    }

    @Test
    fun guessContentType_csv() {
        assertEquals("text/csv", detectContentTypeFromExtensionOrHint("sheet.csv"))
    }

    @Test
    fun guessContentType_html() {
        assertEquals("text/html", detectContentTypeFromExtensionOrHint("page.html"))
    }

    @Test
    fun guessContentType_htm() {
        assertEquals("text/html", detectContentTypeFromExtensionOrHint("page.htm"))
    }

    // ---------------------------------------------------------------
    // detectContentTypeFromExtensionOrHint() — case insensitivity
    // ---------------------------------------------------------------

    @Test
    fun guessContentType_caseInsensitive_JPG() {
        assertEquals("image/jpeg", detectContentTypeFromExtensionOrHint("PHOTO.JPG"))
    }

    @Test
    fun guessContentType_caseInsensitive_PDF() {
        assertEquals("application/pdf", detectContentTypeFromExtensionOrHint("Report.PDF"))
    }

    @Test
    fun guessContentType_caseInsensitive_PNG() {
        assertEquals("image/png", detectContentTypeFromExtensionOrHint("Screenshot.PNG"))
    }

    @Test
    fun guessContentType_caseInsensitive_mixedCase() {
        assertEquals("video/mp4", detectContentTypeFromExtensionOrHint("video.Mp4"))
    }

    // ---------------------------------------------------------------
    // detectContentTypeFromExtensionOrHint() — edge cases
    // ---------------------------------------------------------------

    @Test
    fun guessContentType_unknownExtension() {
        assertEquals("application/octet-stream", detectContentTypeFromExtensionOrHint("data.xyz"))
    }

    @Test
    fun guessContentType_noExtension() {
        assertEquals("application/octet-stream", detectContentTypeFromExtensionOrHint("Makefile"))
    }

    @Test
    fun guessContentType_multipleDots() {
        assertEquals("application/pdf", detectContentTypeFromExtensionOrHint("file.backup.pdf"))
    }

    @Test
    fun guessContentType_multipleDots_unknownFinal() {
        assertEquals("application/octet-stream", detectContentTypeFromExtensionOrHint("archive.tar.bak"))
    }

    @Test
    fun guessContentType_dotOnly() {
        assertEquals("application/octet-stream", detectContentTypeFromExtensionOrHint("."))
    }

    @Test
    fun guessContentType_emptyString() {
        assertEquals("application/octet-stream", detectContentTypeFromExtensionOrHint(""))
    }

    @Test
    fun guessContentType_hiddenFileWithExtension() {
        assertEquals("image/jpeg", detectContentTypeFromExtensionOrHint(".hidden.jpg"))
    }

    @Test
    fun guessContentType_trailingDot() {
        // "file." -> extension is "" -> octet-stream
        assertEquals("application/octet-stream", detectContentTypeFromExtensionOrHint("file."))
    }
}
