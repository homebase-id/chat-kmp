package id.homebase.api.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Confirms [HashUtil.sha256Sync] (pure-Kotlin) produces the same digest as
 * [HashUtil.sha256] (cryptography-kotlin / platform-native). If this ever
 * drifts, OdinId hashing — which uses the sync path through KSerializer —
 * would silently diverge from the async hashing used elsewhere in the codebase
 * and conversation ids would no longer match across paths.
 */
class PureKotlinSha256Test {

    @Test
    fun matchesAsyncSha256_forEmptyInput() = runTest {
        val input = ByteArray(0)
        assertEquals(
            HashUtil.sha256(input).toHex(),
            HashUtil.sha256Sync(input).toHex(),
        )
    }

    @Test
    fun matchesAsyncSha256_forShortString() = runTest {
        val input = "hello world".encodeToByteArray()
        assertEquals(
            HashUtil.sha256(input).toHex(),
            HashUtil.sha256Sync(input).toHex(),
        )
    }

    @Test
    fun matchesAsyncSha256_forDomainName() = runTest {
        // This is the actual shape that flows through OdinId.cachedHash.
        val input = "alice.example.com".encodeToByteArray()
        assertEquals(
            HashUtil.sha256(input).toHex(),
            HashUtil.sha256Sync(input).toHex(),
        )
    }

    @Test
    fun matchesAsyncSha256_forBlockBoundary() = runTest {
        // 64 bytes — exactly one SHA-256 block; covers the padding path that
        // pushes the length word into a second block.
        val input = ByteArray(64) { it.toByte() }
        assertEquals(
            HashUtil.sha256(input).toHex(),
            HashUtil.sha256Sync(input).toHex(),
        )
    }

    @Test
    fun matchesAsyncSha256_forMultiBlock() = runTest {
        val input = ByteArray(1000) { (it * 31).toByte() }
        assertEquals(
            HashUtil.sha256(input).toHex(),
            HashUtil.sha256Sync(input).toHex(),
        )
    }

    @Test
    fun knownVector_emptyString() {
        // FIPS 180-4 test vector: SHA-256("") matches this digest.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HashUtil.sha256Sync(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun knownVector_abc() {
        // FIPS 180-4 test vector: SHA-256("abc") matches this digest.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashUtil.sha256Sync("abc".encodeToByteArray()).toHex(),
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
