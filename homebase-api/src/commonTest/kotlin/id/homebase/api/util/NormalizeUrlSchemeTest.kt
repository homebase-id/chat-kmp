package id.homebase.api.util

import id.homebase.api.client.link.LinkPreviewProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NormalizeUrlSchemeTest {
    @Test
    fun schemeIsLowercasedWhateverTheCase() {
        assertEquals("https://x.com/A", normalizeUrlScheme("Https://x.com/A"))
        assertEquals("http://x.com", normalizeUrlScheme("HTTP://x.com"))
        assertEquals("https://x.com", normalizeUrlScheme("hTtPs://x.com"))
    }

    @Test
    fun wwwGetsHttpsPrepended() {
        assertEquals("https://www.x.com", normalizeUrlScheme("Www.x.com"))
    }

    @Test
    fun nonUrlIsUntouched() {
        assertEquals("Https:", normalizeUrlScheme("Https:"))
        assertFalse(hasHttpScheme("Https:"))
        assertTrue(hasHttpScheme("Https://a.b"))
    }

    @Test
    fun linkPreviewProviderBuildsLowercaseHttpsUrl() {
        assertEquals("https://diku3d.com/", LinkPreviewProvider.standardiseLinkUrl("Https://diku3d.com/"))
        assertEquals("https://diku3d.com", LinkPreviewProvider.standardiseLinkUrl("diku3d.com"))
        assertEquals("https://www.diku3d.com", LinkPreviewProvider.standardiseLinkUrl("Www.diku3d.com"))
    }
}
