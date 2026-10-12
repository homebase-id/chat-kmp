package id.homebase.api.client.drives.files

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadManifest
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.contentHashIv
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.crypto.encryptedPayloadHash
import id.homebase.api.crypto.newHasher
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Golden values from odin-core `docs/payload-hashes.md`, which its own tests pin too. */
class PayloadHashTest {

    private val input = ByteArray(1025) { (it % 251).toByte() }
    private val sha256 = "bc0b6b10b89b9487a12fda2a8cc13194e7091c217aabf8b92846274026f4bcd0"
    private val blake3 = "d00278ae47eb27b34faecf67b4fe263f82d5412916c1ffd97c8cb7fb814b8444"
    private val payloadKey = KeyHeader(
        iv = "3c1f8e5a9b2d47e0a6c4d8f1027b9e35".hexToByteArray(),
        aesKey = SecureByteArray("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToByteArray()),
    )
    private val contentHashIv = "96b524f03187ed4a0c6e725ba8d1349f"
    private val encryptedSha256 =
        "707e52fbded98a6a5f2e2d29efb31bd4a76cc302e70680437b969ddbf2117a5f76efb8fe04637b3eefb16adced53ad6a"
    private val encryptedBlake3 =
        "fe9fbd96981ea69f6b15430fab88d92acbb1e1d5c28b6d54f86c5d195f678955259f6ef4ccb89d8520416e0e20fc1c3c"

    @Test
    fun contentHashIvIsThePayloadIvXorAa() {
        assertEquals(contentHashIv, contentHashIv(payloadKey.iv).toHexString())
    }

    @Test
    fun encryptedContentHashesMatchTheGoldenVectors() = runTest {
        for ((algorithm, digest, encrypted) in listOf(
            Triple(PayloadHashAlgorithm.Sha256, sha256, encryptedSha256),
            Triple(PayloadHashAlgorithm.Blake3, blake3, encryptedBlake3),
        )) {
            val hash = encryptedPayloadHash(algorithm, ByteArray(32), digest.hexToByteArray(), payloadKey)
            assertEquals(encrypted, Base64.decode(hash.contentHash).toHexString(), "$algorithm")
            assertEquals(digest, hash.plaintextContentHash(payloadKey)?.toHexString(), "$algorithm")
        }
    }

    @Test
    fun aContentHashThatDoesNotDecryptReadsAsNull() = runTest {
        val hash = PayloadHash("blake3", "", Base64.encode(encryptedBlake3.hexToByteArray()))
        assertNull(hash.plaintextContentHash(KeyHeader(payloadKey.iv, SecureByteArray(ByteArray(32) { 7 }))))
    }

    @Test
    fun encryptingAFileHashesThePlaintextAndTheCiphertextInOnePass() = runTest {
        val fs = FakeFileSystem()
        val fileOps = OkioFileOperationsProvider(fs, "/cache")
        fs.createDirectories("/in".toPath())
        fs.write("/in/plain".toPath()) { write(input) }

        val hashed = fileOps.encryptWithPayloadHash("/in/plain", "/in/cipher", payloadKey.aesKey, PayloadHashAlgorithm.Blake3)
        val hash = hashed.hash
        assertEquals(input.size.toLong(), hashed.plaintextBytes)

        val ciphertext = fs.read("/in/cipher".toPath()) { readByteArray() }
        assertContentEquals(AesCbc.encrypt(input, payloadKey.aesKey, hashed.iv), ciphertext)
        val storedDigest = PayloadHashAlgorithm.Blake3.newHasher().apply { update(ciphertext) }.digest()
        assertContentEquals(storedDigest, Base64.decode(hash.storedHash))
        assertEquals(blake3, hash.plaintextContentHash(KeyHeader(hashed.iv, payloadKey.aesKey))?.toHexString())
    }

    @Test
    fun theAlgorithmIsReadByNameOrNumberAndSentByName() {
        fun parse(json: String) =
            OdinSystemSerializer.deserialize<PayloadHash>("""{"algorithm":$json,"storedHash":"","contentHash":""}""")
        assertEquals(PayloadHashAlgorithm.Sha256, parse("\"sha256\"").knownAlgorithm)
        assertEquals(PayloadHashAlgorithm.Blake3, parse("2").knownAlgorithm)
        assertTrue(OdinSystemSerializer.serialize(parse("2")).contains("\"algorithm\":\"blake3\""))
    }

    @Test
    fun aHeaderWithAFutureAlgorithmSavesBackUnchanged() = runTest {
        val json = """{"key":"sync_data","hash":{"algorithm":"sha3","storedHash":"cw==","contentHash":"Yw=="}}"""
        val parsed = OdinSystemSerializer.deserialize<PayloadDescriptor>(json)
        assertNull(parsed.hash?.knownAlgorithm)
        assertNull(parsed.hash?.plaintextContentHash(payloadKey))
        assertEquals(parsed, OdinSystemSerializer.deserialize<PayloadDescriptor>(OdinSystemSerializer.serialize(parsed)))
    }

    @Test
    fun anUploadRefusesAHashWithoutItsIv() {
        val hashed = PayloadFile(key = "sync_data", filePath = "/x", hash = PayloadHash("blake3", "cw==", "Yw=="))
        assertFailsWith<IllegalArgumentException> { UploadManifest.build(listOf(hashed), generatePayloadIv = true) }
        assertFailsWith<IllegalArgumentException> { UpdateManifest.build(listOf(hashed), generatePayloadIv = true) }
    }

    @Test
    fun aDescriptorWithoutAHashOmitsIt() {
        assertFalse(OdinSystemSerializer.serialize(PayloadDescriptor(key = "sync_data")).contains("hash"))
        val parsed = OdinSystemSerializer.deserialize<PayloadDescriptor>(
            """{"key":"sync_data","hash":{"algorithm":"blake3","storedHash":"cw==","contentHash":"Yw=="}}"""
        )
        assertEquals(PayloadHashAlgorithm.Blake3, parsed.hash?.knownAlgorithm)
    }
}
