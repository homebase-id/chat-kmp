package id.homebase.core.files

import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the contract the web `PlatformFile.toUploadPath` actual relies on: a picked file's
 * bytes, once materialized, must be READABLE BACK through the same [FileOperationsProvider] the
 * upload pipelines use. The web Gallery/image send bug was exactly this contract being skipped —
 * the raw browser PlatformFile was never copied into okio, so `readFileBytes` couldn't find it.
 *
 * (The web `readBytes()` interop itself can't run without a browser; this covers the shared
 * copy-and-readability logic, which is the part that was missing.)
 */
class UploadMaterializeTest {

    @Test
    fun materializedPathIsReadableBackWithSameProvider() = runTest {
        val fs = InMemoryFileOps()
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)

        val path = materializeBytesToUploadPath(fs, "photo.JPG", bytes)

        // The upload pipeline reads attachment paths via this provider — it must succeed.
        assertEquals(bytes.toList(), fs.readFileBytes(path).toList())
        // Extension is preserved so downstream content-type / muxer logic still works.
        assertTrue(path.endsWith(".JPG"), "expected .JPG suffix, got: $path")
    }

    @Test
    fun materializeHandlesNameWithoutExtension() = runTest {
        val fs = InMemoryFileOps()
        val bytes = byteArrayOf(42)

        val path = materializeBytesToUploadPath(fs, "noextension", bytes)

        assertEquals(bytes.toList(), fs.readFileBytes(path).toList())
        assertTrue(!path.endsWith("."), "no trailing dot when name has no extension: $path")
    }

    @Test
    fun eachMaterializeGetsADistinctPath() = runTest {
        val fs = InMemoryFileOps()
        val a = materializeBytesToUploadPath(fs, "a.png", byteArrayOf(1))
        val b = materializeBytesToUploadPath(fs, "a.png", byteArrayOf(2))
        assertTrue(a != b, "two attachments must not collide on one temp path")
        assertEquals(listOf<Byte>(1), fs.readFileBytes(a).toList())
        assertEquals(listOf<Byte>(2), fs.readFileBytes(b).toList())
    }

    @Test
    fun isUnderDirMatchesOnlyRealDescendants() {
        assertTrue(isUnderDir("/cache", "/cache/upload-temp/a.jpg"))
        assertTrue(isUnderDir("/cache/", "/cache/a.jpg"))
        assertFalse(isUnderDir("/cache", "/cache-other/a.jpg"))
        assertFalse(isUnderDir("/cache", "/cache"))
        assertFalse(isUnderDir("/cache", "content://media/external/images/media/1"))
    }

    @Test
    fun sandboxCopyNameAppendsExtensionWhenPickerNameHasNone() {
        val name = sandboxCopyName("photopicker-1000022602", "image/jpeg")
        assertEquals("photopicker-1000022602.jpg", name)
    }

    @Test
    fun aPickedNameIsMadeSafeBeforeItBecomesAPathOrTheSentName() {
        assertEquals("hyperfile.sys", sandboxCopyName("../../whatever/hyperfile.sys", "application/octet-stream"))
        assertEquals("Q3_ Budget_.xlsx", sandboxCopyName("Q3: Budget?.xlsx", null))
        assertEquals("notes.txt", sandboxCopyName("..\\notes", "text/plain"))
    }

    @Test
    fun sandboxCopyNameKeepsAnExistingExtensionAndSurvivesAnUnmappedMime() {
        assertEquals("IMG_2026.jpeg", sandboxCopyName("IMG_2026.jpeg", "image/jpeg"))
        assertEquals("raw-shot", sandboxCopyName("raw-shot", "image/x-not-a-real-mime"))
        assertEquals("raw-shot", sandboxCopyName("raw-shot", null))
    }
}
