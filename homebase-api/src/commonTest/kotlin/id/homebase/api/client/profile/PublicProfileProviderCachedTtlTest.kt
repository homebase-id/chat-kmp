package id.homebase.api.client.profile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests for [PublicProfileProviderCached.extractTtlMillis] */
class PublicProfileProviderCachedTtlTest {

    private val provider = PublicProfileProviderCached(
        httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
        fileOperationsProvider = FakeFileOperationsProvider()
    )

    // Regression: old default was 300_000L (5 minutes); new default is 604_800_000L (1 week)
    private val oneWeekMs = 604_800_000L

    @Test
    fun nullCacheControl_returnsOneWeek() {
        assertEquals(oneWeekMs, provider.extractTtlMillis(null))
    }

    @Test
    fun emptyCacheControl_returnsOneWeek() {
        assertEquals(oneWeekMs, provider.extractTtlMillis(""))
    }

    @Test
    fun noMaxAgeDirective_returnsOneWeek() {
        assertEquals(oneWeekMs, provider.extractTtlMillis("public, must-revalidate"))
    }

    @Test
    fun maxAge300_clampsToOneWeek() {
        assertEquals(oneWeekMs, provider.extractTtlMillis("max-age=300"))
    }

    @Test
    fun maxAge3600_clampsToOneWeek() {
        assertEquals(oneWeekMs, provider.extractTtlMillis("max-age=3600"))
    }

    @Test
    fun maxAge0_clampsToOneWeek() {
        assertEquals(oneWeekMs, provider.extractTtlMillis("max-age=0"))
    }

    @Test
    fun maxAge86400_clampsToOneWeek() {
        assertEquals(oneWeekMs, provider.extractTtlMillis("public, max-age=86400, must-revalidate"))
    }

    @Test
    fun nonNumericMaxAge_returnsOneWeek() {
        assertEquals(oneWeekMs, provider.extractTtlMillis("max-age=abc"))
    }
}
