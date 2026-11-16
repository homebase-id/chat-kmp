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
}
