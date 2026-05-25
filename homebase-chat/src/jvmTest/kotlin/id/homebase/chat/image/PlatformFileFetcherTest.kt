package id.homebase.chat.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the Coil seam that makes composer/attachment previews render: a picked [PlatformFile]
 * passed as the Coil model must be read back byte-for-byte. On the web a browser-picked file has
 * no path, so this fetcher (reading via FileKit `readBytes()`) is the only thing that loads the
 * preview — if it regresses, web image previews silently go blank again. Runs on the JVM because
 * [PlatformFile] is built from a real [File] here; the fetch path itself is common code.
 */
class PlatformFileFetcherTest {

    private val tmp: File = File.createTempFile("platform-file-fetcher", ".bin")

    @AfterTest
    fun cleanup() {
        tmp.delete()
    }

    @Test
    fun fetch_readsFileBytesIntoImageSource() = runTest {
        val payload = ByteArray(2048) { (it % 251).toByte() }
        tmp.writeBytes(payload)

        val fetcher = PlatformFileFetcher(PlatformFile(tmp), Options(PlatformContext.INSTANCE))
        val result = fetcher.fetch()

        assertTrue(result is SourceFetchResult, "expected a SourceFetchResult")
        val readBack = result.source.source().readByteArray()
        assertContentEquals(payload, readBack, "fetched bytes must round-trip the file content")
    }

    @Test
    fun fetch_emptyFile_yieldsEmptySource() = runTest {
        tmp.writeBytes(ByteArray(0))

        val fetcher = PlatformFileFetcher(PlatformFile(tmp), Options(PlatformContext.INSTANCE))
        val result = fetcher.fetch() as SourceFetchResult

        assertEquals(0L, result.source.source().readByteArray().size.toLong(), "empty file -> empty source")
    }

    @Test
    fun factory_createsFetcherForPlatformFile() {
        val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        val fetcher = PlatformFileFetcher.Factory()
            .create(PlatformFile(tmp), Options(PlatformContext.INSTANCE), imageLoader)
        assertTrue(fetcher is PlatformFileFetcher, "factory must produce a PlatformFileFetcher")
    }
}
