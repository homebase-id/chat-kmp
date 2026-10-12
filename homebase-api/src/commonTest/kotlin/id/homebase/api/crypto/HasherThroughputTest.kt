package id.homebase.api.crypto

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Hashing runs on every Filesync upload and download, so a pathological slowdown on any platform fails here. */
class HasherThroughputTest {

    private fun megabytesPerSecond(newHasher: () -> IncrementalHasher): Double {
        val chunk = ByteArray(64 * 1024) { it.toByte() }
        val chunks = 128
        newHasher().apply { update(chunk) }.digest()
        val start = TimeSource.Monotonic.markNow()
        val hasher = newHasher()
        repeat(chunks) { hasher.update(chunk) }
        hasher.digest()
        val seconds = start.elapsedNow().inWholeMicroseconds.coerceAtLeast(1) / 1_000_000.0
        return chunk.size.toDouble() * chunks / (1024 * 1024) / seconds
    }

    @Test
    fun hashingIsFastEnoughForUploadsAndDownloads() {
        val blake3 = megabytesPerSecond(::Blake3Hasher)
        val sha256 = megabytesPerSecond(::Sha256Hasher)
        println("Hasher throughput: BLAKE3 ${blake3.toInt()} MB/s, SHA-256 ${sha256.toInt()} MB/s")
        assertTrue(blake3 > 5, "BLAKE3 at ${blake3.toInt()} MB/s")
    }
}
