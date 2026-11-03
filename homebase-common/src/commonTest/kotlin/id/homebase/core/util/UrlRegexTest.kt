package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UrlRegexTest {
    @Test
    fun matchesAnyCaseScheme() {
        for (u in listOf("Https://a.test/", "HTTP://a.test", "hTtPs://a.test/x", "www.a.test", "Www.a.test")) {
            assertEquals(u, URL_REGEX.find(u)?.value, u)
        }
    }

    @Test
    fun doesNotMatchBareScheme() {
        assertNull(URL_REGEX.find("Https:"))
        assertNotNull(URL_REGEX.find("see Https://a.test"))
    }
}
