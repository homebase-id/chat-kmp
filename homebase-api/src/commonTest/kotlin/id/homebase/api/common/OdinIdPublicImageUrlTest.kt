package id.homebase.api.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the canonical /pub/image URL shape produced by [publicImageUrl]. The exact string is a
 * contract: `PublicImageFetcher` matches it to route avatar loads through the
 * `homebase-public-images-v2` disk cache, and any drift silently falls back to the plain network
 * fetcher.
 */
class OdinIdPublicImageUrlTest {

    @Test
    fun odinIdOverload_producesCanonicalUrl() {
        assertEquals("https://frodo.digital/pub/image", OdinId("frodo.digital").publicImageUrl())
    }

    @Test
    fun stringOverload_matchesOdinIdOverload() {
        assertEquals(OdinId("frodo.digital").publicImageUrl(), publicImageUrl("frodo.digital"))
    }

    @Test
    fun subdomain_isPreserved() {
        assertEquals(
            "https://sub.frodo.digital/pub/image",
            OdinId("sub.frodo.digital").publicImageUrl(),
        )
    }

    @Test
    fun pathConstant_matchesUrlSuffix() {
        assertEquals("https://frodo.digital$PUB_IMAGE_PATH", OdinId("frodo.digital").publicImageUrl())
    }
}
