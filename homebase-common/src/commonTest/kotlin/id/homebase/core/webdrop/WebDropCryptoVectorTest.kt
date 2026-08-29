package id.homebase.core.webdrop

import id.homebase.api.crypto.AesCbc
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The WebDrop cross-implementation vector. The ciphertext below was produced by THIS module's
 * [AesCbc.encrypt] — the exact code the WebDrop writer runs — from the fixed key/iv/plaintext,
 * and independently confirmed byte-identical with `openssl enc -aes-128-cbc` (PKCS7).
 *
 * The same three constants live in the viewer at
 * odin-js packages/apps/web-drop-app/test/crypto-vector.test.mjs, which must decrypt this
 * ciphertext with WebCrypto. If either side ever fails on it, the wire contract moved — links
 * minted by one implementation would stop opening in the other. Change the constants only in
 * both places at once, and only for a versioned contract change.
 */
@OptIn(ExperimentalEncodingApi::class)
class WebDropCryptoVectorTest {

    private val key = ByteArray(16) { it.toByte() }
    private val iv = ByteArray(16) { (0x10 + it).toByte() }
    private val plaintext = "WebDrop cross-implementation vector v1"
    private val cipherBase64 = "5wEI0MU52bMWHO0F/g8p3tSAu5WAYQniWJKBKP3eg0D6O7S8g098lvh4XuzQ+rSI"

    @Test
    fun theWriterProducesExactlyTheSharedVector() = runTest {
        val cipher = AesCbc.encrypt(plaintext.encodeToByteArray(), key, iv)
        assertEquals(cipherBase64, Base64.encode(cipher))
    }

    @Test
    fun theSharedVectorDecryptsBack() = runTest {
        val plain = AesCbc.decrypt(Base64.decode(cipherBase64), key, iv)
        assertEquals(plaintext, plain.decodeToString())
    }

    @Test
    fun theStreamingPathTheWriterUsesForDataPayloadsMatchesBulk() = runTest {
        // WebDropService encrypts wdr_datN with streamEncryptWithCbc; the vector must hold for it too.
        val chunks = plaintext.encodeToByteArray().toList().chunked(7) { it.toByteArray() }
        val streamed = AesCbc.streamEncryptWithCbc(flowOf(*chunks.toTypedArray()), key, iv)
            .toList()
            .fold(ByteArray(0)) { acc, part -> acc + part }
        assertEquals(cipherBase64, Base64.encode(streamed))
    }

    @Test
    fun aRandomRoundTripSurvives() = runTest {
        val data = ByteArray(1000) { (it * 31).toByte() }
        val cipher = AesCbc.encrypt(data, key, iv)
        assertContentEquals(data, AesCbc.decrypt(cipher, key, iv))
    }
}
