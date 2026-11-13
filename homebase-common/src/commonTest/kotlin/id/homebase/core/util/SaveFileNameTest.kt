package id.homebase.core.util

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

    @Test
    fun aSavedPayloadGetsAnExtensionFromItsContentTypeOnlyWhenItHasNone() {
        assertEquals("contract.pdf", saveFileName("contract.pdf", "chat_web0", "application/octet-stream"))
        assertEquals("contract.pdf", saveFileName("contract", "chat_web0", "application/pdf"))
        assertEquals("chat_web0.webp", saveFileName(null, "chat_web0", "image/webp"))
        assertEquals("chat_web0.bin", saveFileName(null, "chat_web0", "application/octet-stream"))
        assertEquals("passwd.bin", saveFileName("../../etc/passwd", "chat_web0", null))
    }

    @Test
    fun aSavedNameIsNeverAPath() {
        listOf("../x", "..\\x", "a/../../b", "C:evil.txt", "//server/share/f", "..", "/").forEach { raw ->
            val name = saveFileName(raw, "k", "image/png")
            assertTrue(name.none { it == '/' || it == '\\' || it == ':' } && !name.startsWith("."), "$raw -> $name")
        }
    }
}
