package id.homebase.chat.services.builder

import id.homebase.api.client.link.LinkPreview
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class LinkPreviewPayloadBuilderTest {

    private fun svgBase64FromBugLog(): String {
        val bytes = this::class.java.getResourceAsStream("/google_calendar_logo.svg")
            ?.readBytes() ?: error("google_calendar_logo.svg test resource missing")
        return Base64.encode(bytes)
    }


    /**
     * Real server response for `https://diku.dk/` captured from
     * `~/.homebase-chat-dev/logs/homebase.log` on 2026-05-06.
     *
     * If the `/links/extract` endpoint's response shape ever changes,
     * update this constant manually — we deliberately do not hit the
     * live endpoint in tests.
     *
     * The trailing `"type":null` field is unknown to LinkPreview.kt
     * but OdinSystemSerializer has ignoreUnknownKeys = true.
     */
    private val dikuDkServerResponseJson = """
        {"title":"Datalogisk Institut – Københavns Universitet","description":"Velkommen til DIKU, Datalogisk Institut på Københavns Universitet. På hjemmesiden kan du finde informationer om uddannelser, forskning, formidlingsaktiviteter, erhvervssamarbejde og meget mere.","imageUrl":null,"imageWidth":null,"imageHeight":null,"url":"https://diku.dk/","type":null}
    """.trimIndent()

    @Test
    fun build_writesNonEmptyPayloadFile_whenLinkPreviewHasNoImage_dikuDkRepro() = runTest {
        // diku.dk has no og:image; server returns imageUrl=null. Pre-fix the
        // builder wrote a 0-byte temp file and the encryption pipeline later
        // crashed with "Data cannot be empty" inside AesCbc.encrypt.
        val preview = OdinSystemSerializer.deserialize<LinkPreview>(dikuDkServerResponseJson)
        val fakeFileOps = RecordingFileOperationsProvider()

        val bundle = LinkPreviewPayloadBuilder.build(preview, fakeFileOps)

        assertEquals(1, bundle.payloads.size, "Should produce one PayloadFile carrying descriptorContent")
        val payload = bundle.payloads.single()
        val writtenBytes = fakeFileOps.bytesWrittenAt(payload.filePath)
        assertNotNull(writtenBytes, "Builder should have written the payload file")
        assertTrue(
            writtenBytes.isNotEmpty(),
            "Payload bytes must not be empty — encryption pipeline rejects empty data"
        )
        assertTrue(bundle.previewThumbs.isEmpty(), "No image → no thumbnail")
    }

    @Test
    fun build_serializesLinkMetadataIntoDescriptorContent_dikuDkRepro() = runTest {
        // Receiver-compat sanity: descriptorContent must round-trip back into
        // a LinkPreviewDescriptor with the right fields and hasImage=false.
        val preview = OdinSystemSerializer.deserialize<LinkPreview>(dikuDkServerResponseJson)
        val fakeFileOps = RecordingFileOperationsProvider()

        val bundle = LinkPreviewPayloadBuilder.build(preview, fakeFileOps)
        val descriptorJson = bundle.payloads.single().descriptorContent
        assertNotNull(descriptorJson)

        val descriptor = OdinSystemSerializer
            .deserialize<List<LinkPreviewDescriptor>>(descriptorJson)
            .single()

        assertEquals("https://diku.dk/", descriptor.url)
        assertEquals("Datalogisk Institut – Københavns Universitet", descriptor.title)
        assertFalse(descriptor.hasImage, "imageUrl was null → hasImage must be false")
        assertEquals(null, descriptor.imageWidth)
        assertEquals(null, descriptor.imageHeight)
    }

    /**
     * Real server response for the bug-report URL
     * `https://services.msgsndr.com/urls/l/veRVz_mXd`, captured verbatim
     * from /home/seifert/odin/chat-kmp/homebase.log at 2026-05-17T17:34:19.
     * `imageUrl` is a 2180-char `data:image/svg+xml;base64,…` data URI
     * (Google Calendar's SVG logo, 1634 raw bytes after decode).
     *
     * Phase 1 dropped the thumb to keep the outbox unblocked. Phase 3
     * rasterizes the SVG through ImageUtils.rasterizeSvg so the builder
     * produces a real bitmap previewThumbnail (≤ MaxEmbeddedThumbBytes)
     * and the bubble shows the image — no warning triangle, no stall.
     */
    @Test
    fun build_rasterizesSvg_intoBitmapThumb_andFlipsHasImageTrue() = runTest {
        val svgDataUri = "data:image/svg+xml;base64," + svgBase64FromBugLog()
        val preview = LinkPreview(
            title = "Google Calendar",
            url = "https://services.msgsndr.com/urls/l/veRVz_mXd",
            description = "Easier Time Management",
            imageUrl = svgDataUri,
            imageWidth = null,
            imageHeight = null,
        )
        val fakeFileOps = RecordingFileOperationsProvider()

        val bundle = LinkPreviewPayloadBuilder.build(preview, fakeFileOps)

        val payload = bundle.payloads.single()
        val thumb = payload.previewThumbnail
        assertNotNull(thumb, "SVG should rasterize into a real embedded thumb")
        assertEquals("image/webp", thumb.contentType)
        val content = thumb.content
        assertNotNull(content)
        // Decoded raw size must stay under the server cap so the upload
        // succeeds on attempt=1.
        val decodedSize = (content.length / 4) * 3
        assertTrue(
            decodedSize <= 1024,
            "rasterized SVG tiny is ~$decodedSize raw bytes, server cap is 1024"
        )
        assertEquals(1, bundle.previewThumbs.size, "exactly one embedded tiny thumb")

        val descriptor = OdinSystemSerializer
            .deserialize<List<LinkPreviewDescriptor>>(payload.descriptorContent!!)
            .single()
        assertTrue(
            descriptor.hasImage,
            "hasImage must be true so LinkPreviewCard renders the bubble image"
        )
    }

    @Test
    fun build_truncatesTitleAndDescription_whenDescriptorExceedsByteLimit() = runTest {
        val longText = "A".repeat(800)
        val preview = LinkPreview(
            title = longText,
            url = "https://example.com",
            description = longText,
            imageUrl = null,
            imageHeight = null,
            imageWidth = null,
        )
        val fakeFileOps = RecordingFileOperationsProvider()

        val bundle = LinkPreviewPayloadBuilder.build(preview, fakeFileOps)
        val descriptorJson = bundle.payloads.single().descriptorContent
        assertNotNull(descriptorJson)
        val descriptor = OdinSystemSerializer
            .deserialize<List<LinkPreviewDescriptor>>(descriptorJson)
            .single()

        assertTrue(
            descriptor.title.length <= 100,
            "Title should be truncated when descriptor overflows"
        )
        assertTrue(
            descriptor.description.length <= 100,
            "Description should be truncated when descriptor overflows"
        )
    }
}

/**
 * In-memory fake — only `writeBytesToTempFile` is exercised by the builder.
 * Records the bytes per generated path so tests can assert what was written
 * without doing real I/O.
 */
private class RecordingFileOperationsProvider : FileOperationsProvider {
    private val writes = mutableMapOf<String, ByteArray>()
    private var counter = 0

    fun bytesWrittenAt(path: String): ByteArray? = writes[path]

    override suspend fun writeBytesToTempFile(
        bytes: ByteArray,
        prefix: String,
        suffix: String,
    ): String {
        val path = "/fake-temp/${prefix}_${counter++}$suffix"
        writes[path] = bytes
        return path
    }

    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
        error("not used in this test — share-outbound is for chat ShareMedia only")

    // Unused by LinkPreviewPayloadBuilder; throw so any future drift is loud.
    override fun openFileInput(path: String): InputProvider = error("not used in this test")
    override suspend fun readFileBytes(path: String): ByteArray = error("not used in this test")
    override fun deleteTempFile(path: String): Boolean = error("not used in this test")
    override fun getCacheDirectory(): String = error("not used in this test")
    override fun getFileSize(path: String): Long = error("not used in this test")
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used in this test")
}
