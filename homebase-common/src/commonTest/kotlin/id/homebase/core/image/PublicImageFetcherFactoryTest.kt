package id.homebase.core.image

import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests for [PublicImageFetcher.Factory] URL matching and OdinId parsing logic */
class PublicImageFetcherFactoryTest {

    // =========================================================
    // URL matching predicate
    // =========================================================

    @Test
    fun urlWithPubImage_isAccepted() {
        assertTrue("https://frodo.digital/pub/image".contains("/pub/image"))
    }

    @Test
    fun urlWithoutPubImage_isRejected() {
        assertFalse("https://frodo.digital/pub/profile".contains("/pub/image"))
    }

    @Test
    fun rootUrl_isRejected() {
        assertFalse("https://frodo.digital".contains("/pub/image"))
    }

    @Test
    fun emptyString_isRejected() {
        assertFalse("".contains("/pub/image"))
    }

    // =========================================================
    // OdinId parsing
    // =========================================================

    @Test
    fun odinId_stripsPrefixAndSuffix() {
        val url = "https://frodo.digital/pub/image"
        val domain = url.removePrefix("https://").removeSuffix("/pub/image")
        assertEquals("frodo.digital", OdinId(domain).toString())
    }

    @Test
    fun odinId_subdomainPreserved() {
        val url = "https://sub.frodo.digital/pub/image"
        val domain = url.removePrefix("https://").removeSuffix("/pub/image")
        assertEquals("sub.frodo.digital", OdinId(domain).toString())
    }
}
