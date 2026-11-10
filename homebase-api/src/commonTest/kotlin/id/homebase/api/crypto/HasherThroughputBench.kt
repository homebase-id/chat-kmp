package id.homebase.api.crypto

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Runs only with HASHER_BENCH=1 (homebase-api/build.gradle.kts): CI runner speed varies too much for a timing test. */
class HasherThroughputBench {

    private fun megabytesPerSecond(newHasher: () -> IncrementalHasher): Double {
        val chunk = ByteArray(64 * 1024) { it.toByte() }
        newHasher().apply { update(chunk) }.digest()
        val start = TimeSource.Monotonic.markNow()
        val hasher = newHasher()
        var chunks = 0
        // Bounded by time, not bytes: the browser runner fails any test over 2 s, and debug wasm hashes at a few MB/s.
        while (chunks < 128 && start.elapsedNow() < 300.milliseconds) {
            hasher.update(chunk)
            chunks++
        }
        hasher.digest()
        val seconds = start.elapsedNow().inWholeMicroseconds.coerceAtLeast(1) / 1_000_000.0
        return chunk.size.toDouble() * chunks / (1024 * 1024) / seconds
    }

    @Test
    fun hashingIsFastEnoughForUploadsAndDownloads() {
        val blake3 = megabytesPerSecond(::Blake3Hasher)
        val sha256 = megabytesPerSecond(::Sha256Hasher)
        println("Hasher throughput: BLAKE3 ${blake3.toInt()} MB/s, SHA-256 ${sha256.toInt()} MB/s")
        assertTrue(blake3 > 1, "BLAKE3 at ${blake3.toInt()} MB/s")
    }
}
