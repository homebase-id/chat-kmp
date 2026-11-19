package id.homebase.chat.services.builder

import id.homebase.api.file.OkioFileOperationsProvider
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadableImagesTest {
    // A provider where "library/" paths are fetched whole on every read, as an iOS Photos-library asset is.
    private class LibraryProvider(private val fs: FakeFileSystem) : OkioFileOperationsProvider(fs, "/cache") {
        val resolved = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        override fun readsInPlace(path: String) = !path.startsWith("library/")
        override suspend fun resolveToFilePath(path: String): String {
            if (readsInPlace(path)) return path
            resolved += path
            return "/cache/resolved_${resolved.size}.heic"
        }
        override fun deleteTempFile(path: String): Boolean {
            deleted += path
            return true
        }
    }

    private fun image(path: String) = AttachmentInput(filePath = path, contentType = "image/heic")

    @Test
    fun aLibraryPhotoIsResolvedOnceForTheSendAndItsCopyGoesAfter() = runTest {
        val ops = LibraryProvider(FakeFileSystem())
        val attachments = listOf(image("library/A/L0/001"), image("/picked/b.jpg"), AttachmentInput("library/C/L0/001", "video/quicktime"))

        val seen = ops.withReadableImages(attachments) { readable ->
            assertTrue(ops.deleted.isEmpty(), "the copy lives until the send is done with it")
            readable.map { it.filePath }
        }

        assertEquals(listOf("/cache/resolved_1.heic", "/picked/b.jpg", "library/C/L0/001"), seen)
        assertEquals(listOf("library/A/L0/001"), ops.resolved, "only the library photo; a video keeps its own resolve")
        assertEquals(listOf("/cache/resolved_1.heic"), ops.deleted)
    }

    @Test
    fun theCopyGoesEvenWhenTheSendFails() = runTest {
        val ops = LibraryProvider(FakeFileSystem())
        runCatching { ops.withReadableImages(listOf(image("library/A/L0/001"))) { error("upload failed") } }
        assertEquals(listOf("/cache/resolved_1.heic"), ops.deleted)
    }

    @Test
    fun filesThatReadInPlaceAreLeftAlone() = runTest {
        val ops = LibraryProvider(FakeFileSystem())
        val attachments = listOf(image("/picked/a.jpg"))
        ops.withReadableImages(attachments) { assertEquals(attachments, it) }
        assertFalse(ops.resolved.isNotEmpty() || ops.deleted.isNotEmpty())
    }
}
