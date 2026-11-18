package id.homebase.api.file

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** A pending MediaStore entry written as the album save writes it: streamed, then a few bytes written back in place. */
@RunWith(AndroidJUnit4::class)
class ContentUriWriteTest {

    @Test
    fun aPendingEntryIsStreamedThenOverwrittenInPlace() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "content_write_${System.nanoTime()}.bin")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )!!
        try {
            val fileOps = AndroidFileOperationsProvider(context)
            val first = Random(7).nextBytes(300_000)
            val second = Random(8).nextBytes(200_000)
            fileOps.writeDownload(uri.toString(), flowOf(first, second))
            val size = ByteArray(16) { it.toByte() }
            fileOps.overwrite(uri.toString(), 32, size)

            val expected = (first + second).also { size.copyInto(it, 32) }
            assertContentEquals(expected, fileOps.readFileBytes(uri.toString()))
            assertFailsWith<IllegalArgumentException> { fileOps.overwrite(uri.toString(), expected.size - 8L, size) }
            assertContentEquals(expected, fileOps.readFileBytes(uri.toString()), "a refused overwrite changes nothing")
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
