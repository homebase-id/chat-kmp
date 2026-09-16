package id.homebase.core.image

import coil3.toUri
import id.homebase.api.common.OdinId
import id.homebase.api.common.publicImageUrl
import id.homebase.core.image.PublicImageFetcher.Companion.resolveOdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [resolveOdinId] is the consumer side of the `/pub/image` URL `publicImageUrl` builds. If either
 * drifts, avatar loads fall through to the plain NetworkFetcher and bypass the
 * homebase-public-images-v2 cache — the orphan-Coil warning on the Storage screen starts firing.
 */
class PublicImageFetcherFactoryTest {

    @Test
    fun resolveOdinId_uriData_matches() {
        val uri = "https://biggus.dickus.demo.rocks/pub/image".toUri()
        assertEquals("biggus.dickus.demo.rocks", resolveOdinId(uri)?.toString())
    }

    @Test
    fun resolveOdinId_stringData_matches() {
        assertEquals("frodo.digital", resolveOdinId("https://frodo.digital/pub/image")?.toString())
    }

    @Test
    fun resolveOdinId_uriWithSubdomain_preservesOdinId() {
        val uri = "https://sub.frodo.digital/pub/image".toUri()
        assertEquals("sub.frodo.digital", resolveOdinId(uri)?.toString())
    }

    @Test
    fun resolveOdinId_roundTripsCanonicalBuilderUrl() {
        val odinId = OdinId("frodo.digital")
        assertEquals(odinId, resolveOdinId(odinId.publicImageUrl().toUri()))
    }

    // The revision suffix rememberPublicAvatarUrl appends moves the Coil cache key (and, on web,
    // the browser's); it is not part of the identity the fetcher resolves.
    @Test
    fun resolveOdinId_revisionSuffix_isNotPartOfTheIdentity() {
        assertEquals(
            OdinId("frodo.digital"),
            resolveOdinId("https://frodo.digital/pub/image?v=1699999999".toUri()),
        )
        assertEquals(
            OdinId("frodo.digital"),
            resolveOdinId("https://frodo.digital/pub/image?v=1699999999"),
        )
    }

    @Test
    fun nonPublicImageUrls_areRejected() {
        assertNull(resolveOdinId("https://frodo.digital/pub/profile"))
        assertNull(resolveOdinId("https://frodo.digital"))
        assertNull(resolveOdinId("http://frodo.digital/pub/image"))
        assertNull(resolveOdinId(""))
    }

    @Test
    fun malformedHost_fallsThroughInsteadOfThrowing() {
        assertNull(resolveOdinId("https:///pub/image"))
        assertNull(resolveOdinId("https://not a host/pub/image"))
    }

    @Test
    fun resolveOdinId_unsupportedDataType_returnsNull() {
        assertNull(resolveOdinId(ByteArray(0)))
        assertNull(resolveOdinId(42))
        assertNull(resolveOdinId(Any()))
    }
}
