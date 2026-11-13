package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeFileNameTest {

    @Test
    fun anOrdinaryNameIsKept() {
        assertEquals("Report 2026.pdf", safeFileName("Report 2026.pdf"))
        assertEquals("café.txt", safeFileName("café.txt"))
        assertEquals("café.txt", safeFileName("cafe\u0301.txt"), "NFC, so one name is one file on every platform")
    }

    @Test
    fun aPathInTheNameIsReducedToItsLastSegment() {
        assertEquals("passwd", safeFileName("../../etc/passwd"))
        assertEquals("evil.pdf", safeFileName("C:\\Users\\x\\evil.pdf"))
        assertEquals("a.txt", safeFileName("dir/sub/a.txt"))
    }

    @Test
    fun theResultIsNeverAPath() {
        listOf("../x", "..\\x", "a/../../b", "C:evil.txt", "C:\\x", "//server/share/f", "x/", "x\\", "a:b:c", "..", ".", "/", "\\").forEach { raw ->
            listOf(safeFileName(raw), safeFileName(raw, extension = "mp4"), saveFileName(raw, "k", "image/png")).forEach { name ->
                assertTrue(name.none { it == '/' || it == '\\' || it == ':' } && name != "." && name != ".." && !name.startsWith("."), "$raw -> $name")
            }
        }
    }

    @Test
    fun controlAndReservedCharactersAreReplaced() {
        assertEquals("a_b_c.txt", safeFileName("a\u0000b\nc.txt"))
        assertEquals("a_b_c_d.txt", safeFileName("a<b>c:d.txt"))
        assertEquals("q_.txt", safeFileName("q?.txt"))
        assertEquals("say _hi_ _ bye.txt", safeFileName("say \"hi\" | bye.txt"))
    }

    @Test
    fun anEmptyDotOrMissingNameFallsBack() {
        listOf(null, "", "   ", ".", "..", "/", "...").forEach { raw ->
            val name = safeFileName(raw)
            assertTrue(name.isNotEmpty() && name != "." && name != "..", "$raw -> $name")
            assertFalse(name.startsWith("."), "$raw -> $name")
        }
        assertEquals("video.mp4", safeFileName("..", "video", "mp4"))
    }

    @Test
    fun aLeadingDotDoesNotMakeAHiddenFile() {
        assertEquals("bashrc", safeFileName(".bashrc"))
        assertEquals("hidden.txt", safeFileName(".hidden.txt"))
    }

    @Test
    fun windowsDropsATrailingDotOrSpaceSoNeitherIsKept() {
        assertEquals("notes", safeFileName("notes. . "))
        assertEquals("notes.txt", safeFileName("notes.txt  "))
    }

    @Test
    fun aWindowsDeviceNameIsNeverTheWholeStem() {
        assertEquals("_CON", safeFileName("CON"))
        assertEquals("_con.txt", safeFileName("con.txt"))
        assertEquals("_nul.tar.gz", safeFileName("nul.tar.gz"))
        assertEquals("_LPT9.mp4", safeFileName("LPT9", extension = "mp4"))
        assertEquals("console.txt", safeFileName("console.txt"))
    }

    @Test
    fun aVeryLongNameKeepsItsExtensionAndFitsAFilename() {
        val name = safeFileName("x".repeat(600) + ".pdf")
        assertTrue(name.endsWith(".pdf"))
        assertTrue(name.encodeToByteArray().size <= 255, "${name.encodeToByteArray().size} bytes")
    }

    @Test
    fun aLongNameOfWideCharactersIsNotCutMidCharacter() {
        val name = safeFileName("😀".repeat(200) + ".txt")
        assertTrue(name.endsWith(".txt"))
        assertTrue(name.encodeToByteArray().size <= 255)
        assertEquals(name, name.encodeToByteArray().decodeToString(), "round-trips as valid UTF-8")
    }

    @Test
    fun aGivenExtensionReplacesTheNamesOwn() {
        assertEquals("IMG_1234.HEIC.mp4", safeFileName("IMG_1234.HEIC", extension = "mp4"))
        assertEquals("clip.mp4", safeFileName("clip", extension = "mp4"))
        assertEquals("clip.m_u8", safeFileName("clip", extension = "m/u8"))
    }

    @Test
    fun aSavedPayloadGetsAnExtensionFromItsContentTypeOnlyWhenItHasNone() {
        assertEquals("contract.pdf", saveFileName("contract.pdf", "chat_web0", "application/octet-stream"))
        assertEquals("contract.pdf", saveFileName("contract", "chat_web0", "application/pdf"))
        assertEquals("chat_web0.webp", saveFileName(null, "chat_web0", "image/webp"))
        assertEquals("chat_web0.bin", saveFileName(null, "chat_web0", "application/octet-stream"))
        assertEquals("passwd.bin", saveFileName("../../etc/passwd", "chat_web0", null))
    }
}
