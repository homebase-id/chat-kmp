package id.homebase.api.client.drives.upload

import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.common.SecureByteArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalEncodingApi::class)
class UploadValidationTest {

    // ─────────── happy path ───────────

    @Test
    fun happyPath_minimalValidRequest_passes() {
        val request = newRequest()
        request.validateForUpload() // no throw
    }

    @Test
    fun happyPath_validThumbAndDescriptor_passes() {
        val request = newRequest(
            embeddedThumb = embeddedThumbOfRawBytes(HomebaseProtocol.MaxEmbeddedThumbBytes),
            headerContent = "x".repeat(HomebaseProtocol.MaxHeaderContentBytes),
            payloads = listOf(
                samplePayload(
                    descriptor = "x".repeat(HomebaseProtocol.MaxPayloadDescriptorBytes),
                    thumb = embeddedThumbOfRawBytes(HomebaseProtocol.MaxEmbeddedThumbBytes),
                )
            ),
        )
        request.validateForUpload() // exactly at the budget, must still pass
    }

    // ─────────── embedded thumb ───────────

    @Test
    fun fails_whenMessageLevelEmbeddedThumbExceedsCap() {
        val request = newRequest(
            embeddedThumb = embeddedThumbOfRawBytes(HomebaseProtocol.MaxEmbeddedThumbBytes + 1),
        )
        val ex = assertFailsWith<ClientException> { request.validateForUpload() }
        assertEquals(400, ex.status)
        assertTrue(
            ex.message!!.startsWith(VALIDATION_MESSAGE_PREFIX),
            "validation error message should start with $VALIDATION_MESSAGE_PREFIX, got: ${ex.message}"
        )
        assertTrue(
            ex.message!!.contains("Thumbnail size of "),
            "validation error should describe the thumbnail size, got: ${ex.message}"
        )
        assertTrue(
            ex.message!!.contains("metadata.appData.previewThumbnail"),
            "validation error should pinpoint the producer location, got: ${ex.message}"
        )
    }

    @Test
    fun fails_whenPayloadEmbeddedThumbExceedsCap() {
        val request = newRequest(
            payloads = listOf(
                samplePayload(
                    key = "chat_links",
                    thumb = embeddedThumbOfRawBytes(HomebaseProtocol.MaxEmbeddedThumbBytes + 100),
                )
            ),
        )
        val ex = assertFailsWith<ClientException> { request.validateForUpload() }
        assertTrue(ex.message!!.contains("payload[chat_links].previewThumbnail"),
            "should pinpoint the offending payload, got: ${ex.message}")
    }

    // ─────────── header content ───────────

    @Test
    fun fails_whenHeaderContentExceedsCap() {
        val request = newRequest(
            headerContent = "x".repeat(HomebaseProtocol.MaxHeaderContentBytes + 1),
        )
        val ex = assertFailsWith<ClientException> { request.validateForUpload() }
        assertTrue(
            ex.message!!.contains("Header content size of "),
            "should mention header content size, got: ${ex.message}"
        )
    }

    // ─────────── payload descriptor ───────────

    @Test
    fun fails_whenPayloadDescriptorExceedsCap() {
        val request = newRequest(
            payloads = listOf(
                samplePayload(
                    descriptor = "x".repeat(HomebaseProtocol.MaxPayloadDescriptorBytes + 1),
                )
            ),
        )
        val ex = assertFailsWith<ClientException> { request.validateForUpload() }
        assertTrue(
            ex.message!!.contains("Payload descriptor size of "),
            "should mention descriptor size, got: ${ex.message}"
        )
    }

    // ─────────── payload key ───────────

    @Test
    fun fails_whenPayloadKeyIsTooShort() {
        val request = newRequest(payloads = listOf(samplePayload(key = "abc"))) // < 8 chars
        val ex = assertFailsWith<ClientException> { request.validateForUpload() }
        assertTrue(
            ex.message!!.contains("Payload key 'abc'"),
            "should mention the bad key, got: ${ex.message}"
        )
    }

    @Test
    fun fails_whenPayloadKeyContainsUppercase() {
        val request = newRequest(payloads = listOf(samplePayload(key = "ChatLinks"))) // uppercase
        assertFails { request.validateForUpload() }
    }

    @Test
    fun fails_whenPayloadKeyContainsHyphen() {
        val request = newRequest(payloads = listOf(samplePayload(key = "chat-link"))) // hyphen
        assertFails { request.validateForUpload() }
    }

    // ─────────── UpdateFileByUniqueIdRequest mirrors UploadFileRequest ───────────

    @Test
    fun updateRequest_runsTheSameChecks() {
        val request = UpdateFileByUniqueIdRequest(
            driveId = Uuid.random(),
            uniqueId = Uuid.random(),
            keyHeader = keyHeader(),
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArray(16),
                locale = UpdateLocale.Local,
                recipients = emptyList(),
                manifest = UpdateManifest.build(emptyList(), null, emptyList()),
            ),
            metadata = metadataWith(
                embeddedThumb = embeddedThumbOfRawBytes(HomebaseProtocol.MaxEmbeddedThumbBytes + 1),
            ),
            payloads = null,
            thumbnails = null,
        )
        assertFailsWith<ClientException> { request.validateForUpload() }
    }

    // ─────────── helpers ───────────

    private fun newRequest(
        embeddedThumb: EmbeddedThumb? = null,
        headerContent: String? = null,
        payloads: List<PayloadFile> = emptyList(),
    ): UploadFileRequest = UploadFileRequest(
        driveId = Uuid.random(),
        keyHeader = keyHeader(),
        metadata = metadataWith(embeddedThumb = embeddedThumb, content = headerContent),
        payloads = payloads,
    )

    private fun metadataWith(
        embeddedThumb: EmbeddedThumb? = null,
        content: String? = null,
    ): UploadFileMetadata = UploadFileMetadata(
        allowDistribution = true,
        isEncrypted = false,
        appData = UploadAppFileMetaData(
            uniqueId = Uuid.random(),
            content = content,
            previewThumbnail = embeddedThumb,
        ),
    )

    private fun keyHeader(): KeyHeader =
        KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16)))

    private fun samplePayload(
        key: String = "chat_text",
        descriptor: String? = null,
        thumb: EmbeddedThumb? = null,
    ): PayloadFile = PayloadFile(
        key = key,
        filePath = "/fake-temp/$key.dat",
        previewThumbnail = thumb,
        descriptorContent = descriptor,
    )

    // Build an EmbeddedThumb whose base64 content decodes to ~[rawBytes].
    // The validator's estimateRawBytesFromBase64 reverses this: 4 chars
    // → 3 raw bytes minus padding, so length = ceil(rawBytes / 3) * 4.
    private fun embeddedThumbOfRawBytes(rawBytes: Int): EmbeddedThumb {
        val payload = ByteArray(rawBytes) // arbitrary bytes; content shape doesn't matter for size
        return EmbeddedThumb(
            pixelWidth = 20,
            pixelHeight = 20,
            contentType = "image/webp",
            content = Base64.encode(payload),
        )
    }
}
