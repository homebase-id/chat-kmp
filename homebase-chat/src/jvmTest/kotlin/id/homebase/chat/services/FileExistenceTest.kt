package id.homebase.chat.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the scheme-vs-filesystem heuristic in jvmMain's [fileExists].
 * The androidMain implementation is byte-identical, so this also documents
 * the contract that the Android actual must uphold.
 */
class FileExistenceTest {

    // region Scheme-prefixed (opaque) references are assumed valid

    @Test
    fun contentUri_isAssumedValid() {
        assertTrue(fileExists("content://media/external/images/media/12345"))
    }

    @Test
    fun fileUri_isAssumedValid() {
        // Even if the underlying file doesn't exist — we don't resolve file://
        // here; the consumer does.
        assertTrue(fileExists("file:///nonexistent/path/image.jpg"))
    }

    @Test
    fun httpUrl_isAssumedValid() {
        assertTrue(fileExists("https://example.com/image.jpg"))
        assertTrue(fileExists("http://example.com/image.jpg"))
    }

    // endregion

    // region Filesystem paths fall through to the real existence check

    @Test
    fun existingTempFile_returnsTrue() {
        val file = File.createTempFile("file_existence_test_", ".jpg").also { it.deleteOnExit() }
        try {
            assertTrue(fileExists(file.absolutePath))
        } finally {
            file.delete()
        }
    }

    @Test
    fun deletedTempFile_returnsFalse() {
        val file = File.createTempFile("file_existence_test_", ".jpg")
        val path = file.absolutePath
        file.delete()
        assertFalse(fileExists(path))
    }

    @Test
    fun nonexistentPosixPath_returnsFalse() {
        assertFalse(fileExists("/definitely/does/not/exist/image.jpg"))
    }

    @Test
    fun nonexistentWindowsPath_returnsFalse() {
        // Regression for the previous heuristic (`!path.startsWith("/")`) which
        // returned `true` for any Windows path because it doesn't start with /,
        // bypassing the existence check on Windows desktop entirely.
        val path = "C:\\definitely\\does\\not\\exist\\image.jpg"
        assertFalse(fileExists(path))
    }

    // endregion

    // region Edge cases

    @Test
    fun emptyPath_returnsFalse() {
        // No scheme, no file — must fall through to the real check, which says no.
        assertFalse(fileExists(""))
    }

    // endregion
}
