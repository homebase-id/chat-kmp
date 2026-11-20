package id.homebase.api.crypto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Blake3NativeDeviceTest {

    @Test
    fun theNativeHasherRunsOnAPhone() {
        assertNotNull(platformBlake3Hasher(), "the native library loads")
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
