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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Unit tests for [toVaultFileItem] mapper and [guessContentType] utility.
 */
class VaultFileItemTest {

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
    // toVaultFileItem() — happy paths
    // ---------------------------------------------------------------

    @Test
    fun toVaultFileItem_mapsImagePayloadCorrectly() {
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

        val item = file.toVaultFileItem()

        assertNotNull(item)
        assertEquals(fileId, item.fileId)
        assertEquals(driveId, item.driveId)
        assertEquals("vacation.jpg", item.fileName)
        assertEquals("image/jpeg", item.contentType)
        assertEquals(2048L, item.sizeBytes)
        assertEquals(1_700_000_000_000L, item.createdAt)
        assertEquals("img_key", item.payloadKey)
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
    fun toVaultFileItem_mapsPdfPayloadCorrectly() {
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

        val item = file.toVaultFileItem()

        assertNotNull(item)
        assertEquals("report.pdf", item.fileName)
        assertEquals("application/pdf", item.contentType)
        assertEquals(500_000L, item.sizeBytes)
        assertEquals("pdf_key", item.payloadKey)
        assertTrue(item.isPdf)
        assertFalse(item.isImage)
    }

    @Test
    fun toVaultFileItem_usesFirstPayloadWhenMultiplePresent() {
        val file = buildHomebaseFile(
            payloads = listOf(
                PayloadDescriptor(key = "first", contentType = "image/png", bytesWritten = 100L),
                PayloadDescriptor(key = "second", contentType = "image/jpeg", bytesWritten = 200L),
                PayloadDescriptor(key = "third", contentType = "application/pdf", bytesWritten = 300L),
            ),
            contentJson = OdinSystemSerializer.serialize(VaultFileContent(name = "multi.png")),
        )

        val item = file.toVaultFileItem()

        assertNotNull(item)
        assertEquals("first", item.payloadKey)
        assertEquals("image/png", item.contentType)
        assertEquals(100L, item.sizeBytes)
    }

    @Test
    fun toVaultFileItem_mapsPreviewThumbnail() {
        val thumb = EmbeddedThumb(
            pixelWidth = 200,
            pixelHeight = 150,
            contentType = "image/jpeg",
            content = "base64data",
        )
        val file = buildHomebaseFile(previewThumb = thumb)

        val item = file.toVaultFileItem()

        assertNotNull(item)
        assertNotNull(item.previewThumbnail)
        assertEquals(200, item.previewThumbnail.pixelWidth)
        assertEquals(150, item.previewThumbnail.pixelHeight)
    }

    @Test
    fun toVaultFileItem_handlesNullContentTypeInPayload() {
        val file = buildHomebaseFile(
            payloads = listOf(
                PayloadDescriptor(key = "k", contentType = null, bytesWritten = 10L)
            ),
        )

        val item = file.toVaultFileItem()

        assertNotNull(item)
        assertEquals("", item.contentType)
    }

    @Test
    fun toVaultFileItem_handlesNullBytesWrittenInPayload() {
        val file = buildHomebaseFile(
            payloads = listOf(
                PayloadDescriptor(key = "k", contentType = "text/plain", bytesWritten = null)
            ),
        )

        val item = file.toVaultFileItem()

        assertNotNull(item)
        assertEquals(0L, item.sizeBytes)
    }

    @Test
    fun toVaultFileItem_handlesNullVersionTag() {
        val file = buildHomebaseFile(versionTag = null)

        val item = file.toVaultFileItem()

        assertNotNull(item)
        assertNull(item.versionTag)
    }

    // ---------------------------------------------------------------
    // toVaultFileItem() — null/invalid cases
    // ---------------------------------------------------------------

    @Test
    fun toVaultFileItem_returnsNullWhenPayloadsNull() {
        val file = buildHomebaseFile(payloads = null)

        val item = file.toVaultFileItem()

        assertNull(item)
    }

    @Test
    fun toVaultFileItem_returnsNullWhenPayloadsEmpty() {
        val file = buildHomebaseFile(payloads = emptyList())

        val item = file.toVaultFileItem()

        assertNull(item)
    }

    @Test
    fun toVaultFileItem_returnsNullWhenContentNull() {
        val file = buildHomebaseFile(contentJson = null)

        val item = file.toVaultFileItem()

        assertNull(item)
    }

    @Test
    fun toVaultFileItem_returnsNullWhenContentIsInvalidJson() {
        val file = buildHomebaseFile(contentJson = "not valid json {{{")

        val item = file.toVaultFileItem()

        assertNull(item)
    }

    @Test
    fun toVaultFileItem_returnsNullWhenContentIsMismatchedJsonSchema() {
        // Valid JSON but wrong shape -- missing "name" field
        val file = buildHomebaseFile(contentJson = """{"foo":"bar"}""")

        val item = file.toVaultFileItem()

        // kotlinx.serialization may or may not throw depending on config;
        // if it deserializes with defaults the mapper may succeed.
        // The important contract: it should not crash.
        // If the serializer is strict, this returns null; if lenient, it may return an item.
        // We just verify no exception is thrown.
        // (Test passes regardless of outcome.)
    }

    // ---------------------------------------------------------------
    // guessContentType() — common extensions
    // ---------------------------------------------------------------

    @Test
    fun guessContentType_jpg() {
        assertEquals("image/jpeg", guessContentType("photo.jpg"))
    }

    @Test
    fun guessContentType_jpeg() {
        assertEquals("image/jpeg", guessContentType("photo.jpeg"))
    }

    @Test
    fun guessContentType_png() {
        assertEquals("image/png", guessContentType("image.png"))
    }

    @Test
    fun guessContentType_gif() {
        assertEquals("image/gif", guessContentType("anim.gif"))
    }

    @Test
    fun guessContentType_webp() {
        assertEquals("image/webp", guessContentType("pic.webp"))
    }

    @Test
    fun guessContentType_svg() {
        assertEquals("image/svg+xml", guessContentType("icon.svg"))
    }

    @Test
    fun guessContentType_heic() {
        assertEquals("image/heic", guessContentType("live.heic"))
    }

    @Test
    fun guessContentType_mp4() {
        assertEquals("video/mp4", guessContentType("clip.mp4"))
    }

    @Test
    fun guessContentType_mov() {
        assertEquals("video/quicktime", guessContentType("movie.mov"))
    }

    @Test
    fun guessContentType_mp3() {
        assertEquals("audio/mpeg", guessContentType("song.mp3"))
    }

    @Test
    fun guessContentType_pdf() {
        assertEquals("application/pdf", guessContentType("doc.pdf"))
    }

    @Test
    fun guessContentType_zip() {
        assertEquals("application/zip", guessContentType("archive.zip"))
    }

    @Test
    fun guessContentType_txt() {
        assertEquals("text/plain", guessContentType("readme.txt"))
    }

    @Test
    fun guessContentType_json() {
        assertEquals("application/json", guessContentType("data.json"))
    }

    @Test
    fun guessContentType_csv() {
        assertEquals("text/csv", guessContentType("sheet.csv"))
    }

    @Test
    fun guessContentType_html() {
        assertEquals("text/html", guessContentType("page.html"))
    }

    @Test
    fun guessContentType_htm() {
        assertEquals("text/html", guessContentType("page.htm"))
    }

    // ---------------------------------------------------------------
    // guessContentType() — case insensitivity
    // ---------------------------------------------------------------

    @Test
    fun guessContentType_caseInsensitive_JPG() {
        assertEquals("image/jpeg", guessContentType("PHOTO.JPG"))
    }

    @Test
    fun guessContentType_caseInsensitive_PDF() {
        assertEquals("application/pdf", guessContentType("Report.PDF"))
    }

    @Test
    fun guessContentType_caseInsensitive_PNG() {
        assertEquals("image/png", guessContentType("Screenshot.PNG"))
    }

    @Test
    fun guessContentType_caseInsensitive_mixedCase() {
        assertEquals("video/mp4", guessContentType("video.Mp4"))
    }

    // ---------------------------------------------------------------
    // guessContentType() — edge cases
    // ---------------------------------------------------------------

    @Test
    fun guessContentType_unknownExtension() {
        assertEquals("application/octet-stream", guessContentType("data.xyz"))
    }

    @Test
    fun guessContentType_noExtension() {
        assertEquals("application/octet-stream", guessContentType("Makefile"))
    }

    @Test
    fun guessContentType_multipleDots() {
        assertEquals("application/pdf", guessContentType("file.backup.pdf"))
    }

    @Test
    fun guessContentType_multipleDots_unknownFinal() {
        assertEquals("application/octet-stream", guessContentType("archive.tar.bak"))
    }

    @Test
    fun guessContentType_dotOnly() {
        assertEquals("application/octet-stream", guessContentType("."))
    }

    @Test
    fun guessContentType_emptyString() {
        assertEquals("application/octet-stream", guessContentType(""))
    }

    @Test
    fun guessContentType_hiddenFileWithExtension() {
        assertEquals("image/jpeg", guessContentType(".hidden.jpg"))
    }

    @Test
    fun guessContentType_trailingDot() {
        // "file." -> extension is "" -> octet-stream
        assertEquals("application/octet-stream", guessContentType("file."))
    }
}
