package id.homebase.api.file

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentUriReadTest {

    @Test
    fun aContentUriReadsInFullChunksAndWhole() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = Random(5).nextBytes(3 * 256 * 1024 + 1234)
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "content_read_${System.nanoTime()}.bin") },
        )!!
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            val chunks = AndroidFileOperationsProvider(context).readFileAsFlow(uri.toString(), 256 * 1024).toList()
            assertEquals(listOf(256 * 1024, 256 * 1024, 256 * 1024, 1234), chunks.map { it.size })
            assertContentEquals(bytes, chunks.reduce(ByteArray::plus))
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun aContentUriOpenedOnceReadsAnyRange() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = Random(7).nextBytes(300_000)
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "content_ranges_${System.nanoTime()}.bin") },
        )!!
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            val files = AndroidFileOperationsProvider(context)
            files.openRanges(uri.toString()).use { ranges ->
                assertContentEquals(bytes.copyOfRange(299_990, 300_000), ranges.read(299_990, 100), "fewer at the end")
                assertContentEquals(bytes.copyOfRange(0, 16), ranges.read(0, 16))
                assertContentEquals(bytes.copyOfRange(123_456, 123_556), ranges.read(123_456, 100))
                assertEquals(300_000L, ranges.size())
            }
            assertContentEquals(bytes.copyOfRange(5, 105), files.readFileRange(uri.toString(), 5, 100))
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
