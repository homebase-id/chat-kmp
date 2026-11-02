package id.homebase.core.ui.screens.openedfile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenedFileNameTest {

    @Test
    fun anOrdinaryNameIsKept() {
        assertEquals("Report 2026.pdf", sanitizeOpenedFileName("Report 2026.pdf"))
        assertEquals("café.txt", sanitizeOpenedFileName("café.txt"))
    }

    @Test
    fun aPathInTheNameIsReducedToItsLastSegment() {
        assertEquals("passwd", sanitizeOpenedFileName("../../etc/passwd"))
        assertEquals("evil.pdf", sanitizeOpenedFileName("C:\\Users\\x\\evil.pdf"))
        assertEquals("a.txt", sanitizeOpenedFileName("dir/sub/a.txt"))
    }

    @Test
    fun controlAndReservedCharactersAreReplaced() {
        assertEquals("a_b_c.txt", sanitizeOpenedFileName("a\u0000b\nc.txt"))
        assertEquals("a_b_c_d.txt", sanitizeOpenedFileName("a<b>c:d.txt"))
        assertEquals("q_.txt", sanitizeOpenedFileName("q?.txt"))
    }

    @Test
    fun anEmptyDotOrMissingNameFallsBack() {
        listOf(null, "", "   ", ".", "..", "/", "...").forEach { raw ->
            val name = sanitizeOpenedFileName(raw)
            assertTrue(name.isNotEmpty() && name != "." && name != "..", "$raw -> $name")
            assertFalse(name.startsWith("."), "$raw -> $name")
        }
    }

    @Test
    fun aLeadingDotDoesNotMakeAHiddenFile() {
        assertEquals("bashrc", sanitizeOpenedFileName(".bashrc"))
        assertEquals("hidden.txt", sanitizeOpenedFileName(".hidden.txt"))
    }

    @Test
    fun aVeryLongNameKeepsItsExtensionAndFitsAFilename() {
        val name = sanitizeOpenedFileName("x".repeat(600) + ".pdf")
        assertTrue(name.endsWith(".pdf"))
        assertTrue(name.encodeToByteArray().size <= 255, "${name.encodeToByteArray().size} bytes")
    }

    @Test
    fun aLongNameOfWideCharactersIsNotCutMidCharacter() {
        val name = sanitizeOpenedFileName("😀".repeat(200) + ".txt")
        assertTrue(name.endsWith(".txt"))
        assertTrue(name.encodeToByteArray().size <= 255)
        assertEquals(name, name.encodeToByteArray().decodeToString(), "round-trips as valid UTF-8")
    }
}
