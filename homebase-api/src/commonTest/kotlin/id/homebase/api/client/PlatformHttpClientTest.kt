package id.homebase.api.client

import id.homebase.api.client.drives.cache.THUMBNAIL_FETCH_CONCURRENCY
import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformHttpClientTest {
    @Test
    fun perHostLimitCoversThumbnailFetches() {
        assertTrue(MAX_REQUESTS_PER_HOST > THUMBNAIL_FETCH_CONCURRENCY)
    }
}
