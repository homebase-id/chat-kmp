package id.homebase.chat.contactcard

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.image.ImageSize
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalEncodingApi::class, ExperimentalUuidApi::class)
class ContactCardPhotoTest {

    private val key = ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB + "0"
    private val iv = Base64.encode(ByteArray(16) { it.toByte() })

    private fun photo(
        key: String = this.key,
        contentType: String? = "image/jpeg",
        iv: String? = this.iv,
    ) = PayloadDescriptor(key = key, contentType = contentType, iv = iv)

    private fun data(payloads: List<PayloadDescriptor>?) = contactCardPhotoData(
        payloads = payloads,
        driveId = Uuid.random(),
        fileId = Uuid.random(),
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16) { 7 })),
        previewThumbnail = null,
    )

    @Test
    fun `a card with no payloads has no photo`() {
        assertNull(contactCardPhotoPayload(null))
        assertNull(contactCardPhotoPayload(emptyList()))
    }

    @Test
    fun `the chat_web image payload is the photo`() {
        val found = contactCardPhotoPayload(listOf(photo()))
        assertEquals(key, assertNotNull(found).key)
    }

    @Test
    fun `a non-image payload under the same key is not a photo`() {
        // The key is shared with other web-ish payloads; only an image is an avatar.
        assertNull(contactCardPhotoPayload(listOf(photo(contentType = "application/json"))))
        assertNull(contactCardPhotoPayload(listOf(photo(contentType = null))))
    }

    @Test
    fun `an image under an unrelated key is not a photo`() {
        assertNull(contactCardPhotoPayload(listOf(photo(key = "otherpay"))))
    }

    @Test
    fun `the photo is found among other payloads`() {
        val payloads = listOf(photo(key = "otherpay"), photo(contentType = "text/plain"), photo())
        assertEquals(key, assertNotNull(contactCardPhotoPayload(payloads)).key)
    }

    @Test
    fun `image data takes the AES key from the message and the IV from the payload`() {
        val built = assertNotNull(data(listOf(photo())))

        assertEquals(key, built.payloadKey)
        assertContentEqualsHex(Base64.decode(iv), built.keyHeader?.iv)
        assertContentEqualsHex(ByteArray(16) { 7 }, built.keyHeader?.aesKey?.toByteArray())
    }

    @Test
    fun `a payload with no IV yields no image data`() {
        // Undecryptable: rendering it would spin forever rather than fall back to initials.
        assertNull(data(listOf(photo(iv = null))))
        assertNull(data(listOf(photo(iv = "not base64 @@@"))))
    }

    @Test
    fun `chat payloads are always treated as encrypted`() {
        assertEquals(true, assertNotNull(data(listOf(photo()))).isEncrypted)
    }

    @Test
    fun `the loader is told which thumbnail sizes actually exist`() {
        // Device log, build of 2026-08-18: with this empty the loader guessed 121x121, which the
        // server never stored, and logged a non-retriable 404 before falling back.
        val withThumbs = photo().copy(
            thumbnails = listOf(
                ThumbnailDescriptor(pixelWidth = 300, pixelHeight = 300, contentType = "image/jpeg"),
                ThumbnailDescriptor(pixelWidth = 600, pixelHeight = 600, contentType = "image/jpeg"),
            ),
        )

        val built = assertNotNull(data(listOf(withThumbs)))

        assertEquals(listOf(ImageSize(300, 300), ImageSize(600, 600)), built.availableThumbSizes)
        assertEquals(ImageSize.THUMB_SMALL, built.requestedSize)
    }

    private fun assertContentEqualsHex(expected: ByteArray, actual: ByteArray?) {
        assertEquals(expected.toList(), assertNotNull(actual).toList())
    }
}
