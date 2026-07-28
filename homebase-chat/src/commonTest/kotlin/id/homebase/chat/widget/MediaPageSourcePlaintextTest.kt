package id.homebase.chat.widget

import id.homebase.chat.services.LocalAttachmentContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The unencrypted (public feed post) classification: a payload with no IV on an
 * unencrypted file is plaintext-and-ready, not pending. Chat media is always
 * encrypted, so every chat case must still land on exactly the source it did before
 * [resolveMediaPageSource] learned about plaintext.
 */
class MediaPageSourcePlaintextTest {

    private val iv = ByteArray(16) { it.toByte() }

    private fun imageContext(path: String) =
        LocalAttachmentContext.Image(localFilePath = path, aspectRatio = null)

    @Test
    fun `unencrypted payload with no iv is remote plaintext, not pending`() {
        val result = resolveMediaPageSource(
            localContext = null,
            rawIvPresent = false,
            decodedIv = null,
            isEncrypted = false,
        )
        assertIs<MediaPageSource.Remote>(result)
        assertNull(result.iv, "a plaintext payload must carry no key material")
    }

    @Test
    fun `encrypted payload with no iv stays pending`() {
        // Chat's default: a missing IV on an encrypted file is a still-uploading payload.
        assertEquals(
            MediaPageSource.Pending,
            resolveMediaPageSource(null, rawIvPresent = false, decodedIv = null, isEncrypted = true),
        )
    }

    @Test
    fun `encryption default is on, so chat callers keep the pending classification`() {
        assertEquals(
            resolveMediaPageSource(null, rawIvPresent = false, decodedIv = null, isEncrypted = true),
            resolveMediaPageSource(null, rawIvPresent = false, decodedIv = null),
        )
    }

    @Test
    fun `a decodable iv still takes the encrypted remote path`() {
        // Belt and braces: even flagged unencrypted, a real IV is honoured rather than dropped.
        val encrypted = resolveMediaPageSource(null, rawIvPresent = true, decodedIv = iv, isEncrypted = true)
        assertIs<MediaPageSource.Remote>(encrypted)
        assertContentEquals(iv, encrypted.iv)

        val flaggedPlaintext = resolveMediaPageSource(null, rawIvPresent = true, decodedIv = iv, isEncrypted = false)
        assertIs<MediaPageSource.Remote>(flaggedPlaintext)
        assertContentEquals(iv, flaggedPlaintext.iv)
    }

    @Test
    fun `an encrypted corrupt iv is still unavailable`() {
        assertEquals(
            MediaPageSource.Unavailable,
            resolveMediaPageSource(null, rawIvPresent = true, decodedIv = null, isEncrypted = true),
        )
    }

    @Test
    fun `a local original still wins on an unencrypted file`() {
        val result = resolveMediaPageSource(
            localContext = imageContext("/tmp/photo.jpg"),
            rawIvPresent = false,
            decodedIv = null,
            isEncrypted = false,
        )
        assertIs<MediaPageSource.LocalFile>(result)
        assertEquals("/tmp/photo.jpg", result.path)
    }
}
