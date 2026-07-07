package id.homebase.core.image

import coil3.toUri
import id.homebase.api.common.OdinId
import id.homebase.api.common.publicImageUrl
import id.homebase.core.image.PublicImageFetcher.Companion.resolveOdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // =========================================================
    // Factory data-resolution — REGRESSION COVERAGE
    //
    // Coil 3 maps a String model to coil3.Uri BEFORE Fetcher-factory
    // matching. A Factory<String> is therefore never polled for http(s)
    // URLs — Coil only sees Uris at that stage, falls through to the
    // built-in NetworkFetcher, and our cache layer is bypassed.
    // Factory<Uri> + resolveOdinId() is the fix.
    //
    // resolveOdinId(data: Any) accepts both Uri (production path, what
    // Coil hands us) and String (test convenience) so these tests can
    // exercise both shapes without instantiating a real Coil pipeline.
    //
    // If any of these tests fail, the Factory match logic broke and the
    // orphan-Coil warning on the Storage screen should start firing on
    // the next run (red card + Logger.e from StorageSettingsViewModel).
    // =========================================================

    @Test
    fun resolveOdinId_uriData_matches() {
        val uri = "https://biggus.dickus.demo.rocks/pub/image".toUri()
        assertEquals("biggus.dickus.demo.rocks", resolveOdinId(uri)?.toString())
    }

    @Test
    fun resolveOdinId_stringData_matches() {
        val odinId = resolveOdinId("https://frodo.digital/pub/image")
        assertEquals("frodo.digital", odinId?.toString())
    }

    @Test
    fun resolveOdinId_uriDataForProfileUrl_returnsNull() {
        val uri = "https://frodo.digital/pub/profile".toUri()
        assertNull(resolveOdinId(uri))
    }

    @Test
    fun resolveOdinId_stringDataForProfileUrl_returnsNull() {
        assertNull(resolveOdinId("https://frodo.digital/pub/profile"))
    }

    @Test
    fun resolveOdinId_unsupportedDataType_returnsNull() {
        // ByteArray, Int, Any — anything that isn't a Uri or String must be
        // rejected without throwing, so the factory can cleanly fall through
        // to other fetchers in Coil's registry.
        assertNull(resolveOdinId(ByteArray(0)))
        assertNull(resolveOdinId(42))
        assertNull(resolveOdinId(Any()))
    }

    @Test
    fun resolveOdinId_uriWithSubdomain_preservesOdinId() {
        val uri = "https://sub.frodo.digital/pub/image".toUri()
        assertEquals("sub.frodo.digital", resolveOdinId(uri)?.toString())
    }

    // =========================================================
    // Builder ↔ matcher round-trip
    //
    // publicImageUrl() (homebase-api) is the single producer of the
    // /pub/image URL; resolveOdinId() is its consumer-side matcher. If
    // either side drifts, avatar loads silently fall through to the
    // plain NetworkFetcher and bypass the homebase-public-images-v2
    // cache — this locks the two together.
    // =========================================================

    @Test
    fun resolveOdinId_roundTripsCanonicalBuilderUrl() {
        val odinId = OdinId("frodo.digital")
        assertEquals(odinId, resolveOdinId(odinId.publicImageUrl().toUri()))
    }

    // =========================================================
    // Cache-busting query string (?v=<lastModified>) — see PublicAvatar's
    // cacheBustKey param. The suffix match must still resolve correctly with
    // one appended, or the owner's own avatar refresh silently stops using
    // this Fetcher (and its cache) entirely.
    // =========================================================

    @Test
    fun resolveOdinId_uriWithCacheBustQuery_matches() {
        val uri = "https://frodo.digital/pub/image?v=1699999999".toUri()
        assertEquals("frodo.digital", resolveOdinId(uri)?.toString())
    }

    @Test
    fun resolveOdinId_stringWithCacheBustQuery_matches() {
        val odinId = resolveOdinId("https://frodo.digital/pub/image?v=1699999999")
        assertEquals("frodo.digital", odinId?.toString())
    }
}
