package id.homebase.core.util

import io.github.vinceglb.filekit.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `PlatformFile.contentType()` is what ~20 call sites now use instead of spelling out
 * `resolveContentType(f.name, f.mimeType()?.toString())` or — worse — reading `mimeType()` raw.
 * Both parameters are `String?`, so a swapped argument order would compile and silently drop the
 * extension fallback. These pin the delegation.
 */
class PlatformFileContentTypeTest {

    @Test
    fun resolvesFromTheExtensionWhenThePlatformHasNoMime() {
        assertEquals("image/png", PlatformFile("/tmp/does-not-exist.png").contentType())
    }

    @Test
    fun neverReturnsEmpty_soStartsWithChecksAreSafe() {
        // The raw-mime call sites this replaced used `mimeType()?.toString().orEmpty()` and then
        // `startsWith("video/")`; an extension-less photo-picker name gave them "" (#1149).
        assertEquals("application/octet-stream", PlatformFile("/tmp/photopicker-1000022602").contentType())
    }
}
