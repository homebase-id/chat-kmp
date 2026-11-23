package id.homebase.core.util

import id.homebase.api.file.extensionForContentType
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveFileNameTest {

    @Test
    fun aContentTypeLendsOnlyAPlainExtension() {
        assertEquals("pdf", extensionForContentType("application/pdf"))
        assertEquals("x-foo", extensionForContentType("application/x-foo"))
        assertEquals("txt", extensionForContentType("Text/Plain; charset=utf-8"))
        listOf(null, "", "image", "image/../../x", "a/b/c", "application/octet-stream", "image/svg+x", "text/a.b").forEach {
            assertEquals("bin", extensionForContentType(it), it)
        }
    }

    private val at = 1_795_430_130_000L
    private val utc = TimeZone.UTC

    @Test
    fun aNamelessPayloadGetsADatedNameWithTheBytesExtension() {
        assertEquals("Homebase_20261123_103530.webp", saveFileName(null, "image/webp", at, utc))
        assertEquals("Homebase_20261123_103530.jpg", saveFileName("  ", "image/jpeg", at, utc))
        assertEquals("Homebase_20261123_103530.bin", saveFileName(null, "application/octet-stream", at, utc))
    }

    @Test
    fun aNameKeepsItsExtensionWhenItAgreesWithTheBytes() {
        assertEquals("contract.pdf", saveFileName("contract.pdf", "application/octet-stream", at, utc))
        assertEquals("contract.pdf", saveFileName("contract", "application/pdf", at, utc))
        assertEquals("photo.jpeg", saveFileName("photo.jpeg", "image/jpeg", at, utc))
        assertEquals("IMG.JPG", saveFileName("IMG.JPG", "image/jpeg", at, utc))
    }

    @Test
    fun aNameWhoseExtensionDisagreesWithTheBytesGetsTheBytesExtension() {
        assertEquals("photo.png", saveFileName("photo.jpg", "image/png", at, utc))
        assertEquals("v2.final.pdf", saveFileName("v2.final", "application/pdf", at, utc))
        assertEquals("clip.mp4", saveFileName("clip.mov", "video/mp4", at, utc))
    }

    @Test
    fun aSavedNameIsNeverAPath() {
        listOf("../x", "..\\x", "a/../../b", "C:evil.txt", "//server/share/f", "..", "/").forEach { raw ->
            val name = saveFileName(raw, "image/png", at, utc)
            assertTrue(name.none { it == '/' || it == '\\' || it == ':' } && name != "." && name != "..", "$raw -> $name")
        }
    }
}
