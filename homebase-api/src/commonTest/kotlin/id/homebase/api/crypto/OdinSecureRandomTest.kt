package id.homebase.api.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guards the CSPRNG behind every AES key, IV and GCM nonce in the app.
 *
 * The regression these exist for: [ByteArrayUtil.getRndByteArray] used to return
 * `kotlin.random.Random.Default.nextBytes(n)` while documenting itself as
 * cryptographically safe.
 */
class OdinSecureRandomTest {

    @Test
    fun zeroLengthIsEmpty() {
        assertEquals(0, ByteArrayUtil.getRndByteArray(0).size)
    }

    @Test
    fun negativeLengthThrows() {
        assertFailsWith<IllegalArgumentException> { ByteArrayUtil.getRndByteArray(-1) }
    }

    @Test
    fun requestedSizeIsHonoured() {
        for (size in listOf(1, 12, 16, 32, 64, 128)) {
            assertEquals(size, ByteArrayUtil.getRndByteArray(size).size)
        }
    }

    @Test
    fun drawsDoNotRepeat() {
        val seen = HashSet<String>()
        repeat(1000) {
            val draw = ByteArrayUtil.getRndByteArray(32).toHexString()
            assertTrue(seen.add(draw), "duplicate 32-byte draw — the generator is not random")
        }
    }

    /**
     * The real regression guard: a seeded PRNG produces the same first draw in every
     * process, so a fixed-seed sequence must never match what we hand out.
     */
    @Test
    fun outputDoesNotMatchASeededPrng() {
        for (seed in listOf(0, 1, 42, 12345)) {
            assertNotEquals(
                Random(seed).nextBytes(32).toHexString(),
                ByteArrayUtil.getRndByteArray(32).toHexString(),
                "output matches kotlin.random.Random($seed) — the CSPRNG delegation is gone",
            )
        }
    }

    /** Every byte value should show up across 100 KB; a stub returning zeros would not. */
    @Test
    fun outputCoversTheWholeByteRange() {
        val counts = IntArray(256)
        repeat(100) {
            for (b in ByteArrayUtil.getRndByteArray(1024)) {
                counts[b.toInt() and 0xFF]++
            }
        }
        assertEquals(0, counts.count { it == 0 }, "some byte values never appeared")
    }

    @Test
    fun outputPassesTheExistingStrongKeyCheck() {
        repeat(1000) {
            assertTrue(ByteArrayUtil.isStrongKey(ByteArrayUtil.getRndByteArray(16)))
        }
    }

    @Test
    fun randomGuidsAreDistinct() {
        val seen = HashSet<String>()
        repeat(1000) {
            assertTrue(seen.add(ByteArrayUtil.getRandomCryptoGuid().toString()))
        }
    }

    @Test
    fun wipeStillClears() {
        val bytes = ByteArrayUtil.getRndByteArray(32)
        ByteArrayUtil.wipeByteArray(bytes)
        assertContentEquals(ByteArray(32), bytes)
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
