package id.homebase.api.crypto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** On a phone, Blake3Hasher runs the native BLAKE3; it must hash exactly as the Kotlin port does. */
@RunWith(AndroidJUnit4::class)
class Blake3NativeDeviceTest {

    @Test
    fun theNativeHasherRunsOnAPhone() {
        assertNotNull(platformBlake3Hasher(), "the native library loads")
    }

    @Test
    fun nativeAndKotlinAgreeAcrossBlockChunkAndTreeBoundaries() {
        val random = Random(2431)
        // Around BLAKE3's 64-byte blocks, 1024-byte chunks, and the tree's merges.
        for (length in listOf(0, 1, 63, 64, 65, 1023, 1024, 1025, 2048, 3072, 4096, 16 * 1024 + 7, 1024 * 1024 + 3)) {
            val data = random.nextBytes(length)
            for (piece in listOf(1, 7, 64, 1000, 64 * 1024, maxOf(1, length))) {
                if (piece == 1 && length > 4096) continue
                val native = Blake3Hasher().apply { for (at in data.indices step piece) update(data, at, minOf(piece, data.size - at)) }.digest()
                val kotlin = PortableBlake3().apply { update(data) }.digest()
                assertContentEquals(kotlin, native, "length $length, pieces of $piece")
            }
        }
    }

    /** Not a check: run with `-e hashBench true`; the numbers go to MEDIA_BENCHMARKS.md. */
    @Test
    fun throughput() {
        if (InstrumentationRegistry.getArguments().getString("hashBench") != "true") return
        val chunk = ByteArray(1024 * 1024) { it.toByte() }
        for ((name, make) in listOf<Pair<String, () -> IncrementalHasher>>("native" to ::Blake3Hasher, "kotlin" to ::PortableBlake3)) {
            make().apply { update(chunk) }.digest()
            val started = System.nanoTime()
            val hasher = make()
            repeat(64) { hasher.update(chunk) }
            hasher.digest()
            Log.i("Media3Benchmark", "BLAKE3 $name: ${(64 / ((System.nanoTime() - started) / 1e9)).toInt()} MB/s")
        }
    }
}
