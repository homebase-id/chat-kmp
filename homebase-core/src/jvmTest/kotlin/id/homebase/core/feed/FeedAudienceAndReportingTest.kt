package id.homebase.core.feed

import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.common.OdinId
import id.homebase.core.feed.services.PostAudience
import id.homebase.core.feed.services.ReportingUrlProvider
import id.homebase.core.feed.services.isRestricted
import id.homebase.core.feed.services.toPostAudience
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostAudienceTest {

    @Test
    fun noAclIsPublic() {
        assertEquals(PostAudience.Public, (null as AccessControlList?).toPostAudience())
    }

    @Test
    fun securityGroupsMapToAudiences() {
        assertEquals(PostAudience.Public, acl("anonymous").toPostAudience())
        assertEquals(PostAudience.Authenticated, acl("authenticated").toPostAudience())
        assertEquals(PostAudience.AutoConnected, acl("autoconnected").toPostAudience())
        assertEquals(PostAudience.Connections, acl("connected").toPostAudience())
        assertEquals(PostAudience.Owner, acl("owner").toPostAudience())
    }

    @Test
    fun securityGroupMatchIsCaseInsensitive() {
        assertEquals(PostAudience.Public, acl("Anonymous").toPostAudience())
        assertEquals(PostAudience.Connections, acl("CONNECTED").toPostAudience())
    }

    @Test
    fun connectedWithCirclesIsCircles() {
        val restricted = AccessControlList(
            requiredSecurityGroup = "connected",
            circleIdList = listOf("11111111-1111-1111-1111-111111111111"),
        )
        assertEquals(PostAudience.Circles, restricted.toPostAudience())
    }

    @Test
    fun unknownOrMissingSecurityGroupIsOwnerNotPublic() {
        assertEquals(PostAudience.Owner, acl("something-new").toPostAudience())
        assertEquals(PostAudience.Owner, acl("").toPostAudience())
        assertEquals(PostAudience.Owner, AccessControlList().toPostAudience())
    }

    @Test
    fun onlyPublicAndAuthenticatedAreUnrestricted() {
        assertFalse(PostAudience.Public.isRestricted)
        assertFalse(PostAudience.Authenticated.isRestricted)
        assertTrue(PostAudience.AutoConnected.isRestricted)
        assertTrue(PostAudience.Connections.isRestricted)
        assertTrue(PostAudience.Circles.isRestricted)
        assertTrue(PostAudience.Owner.isRestricted)
    }

    private fun acl(group: String) = AccessControlList(requiredSecurityGroup = group)
}

class ReportingUrlProviderTest {

    @Test
    fun usesTheAuthorsConfiguredReportingUrl() = runTest {
        val provider = providerReturning(""" {"url":"https://example.org/abuse"} """)
        assertEquals("https://example.org/abuse", provider.reportUrlFor(OdinId("frodo.baggins.me")))
    }

    @Test
    fun requestsTheAuthorsOwnHost() = runTest {
        var requested: String? = null
        val engine = MockEngine { request ->
            requested = request.url.toString()
            respond(
                content = """{"url":"https://example.org/abuse"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        ReportingUrlProvider(HttpClient(engine)).reportUrlFor(OdinId("frodo.baggins.me"))
        assertEquals("https://frodo.baggins.me/config/reporting", requested)
    }

    @Test
    fun fallsBackWhenTheIdentityHasNoReportingConfig() = runTest {
        val provider = ReportingUrlProvider(
            HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
        )
        assertEquals(
            ReportingUrlProvider.DEFAULT_REPORT_URL,
            provider.reportUrlFor(OdinId("frodo.baggins.me")),
        )
    }

    @Test
    fun fallsBackOnMalformedOrEmptyBody() = runTest {
        assertEquals(
            ReportingUrlProvider.DEFAULT_REPORT_URL,
            providerReturning("not json at all").reportUrlFor(OdinId("frodo.baggins.me")),
        )
        assertEquals(
            ReportingUrlProvider.DEFAULT_REPORT_URL,
            providerReturning("""{"url":""}""").reportUrlFor(OdinId("frodo.baggins.me")),
        )
        assertEquals(
            ReportingUrlProvider.DEFAULT_REPORT_URL,
            providerReturning("{}").reportUrlFor(OdinId("frodo.baggins.me")),
        )
    }

    @Test
    fun fallsBackWhenTheHostIsUnreachable() = runTest {
        val provider = ReportingUrlProvider(
            HttpClient(MockEngine { throw java.io.IOException("offline") }),
        )
        assertEquals(
            ReportingUrlProvider.DEFAULT_REPORT_URL,
            provider.reportUrlFor(OdinId("frodo.baggins.me")),
        )
    }

    private fun providerReturning(body: String) = ReportingUrlProvider(
        HttpClient(
            MockEngine {
                respond(
                    content = body,
                    headers = headersOf(
                        HttpHeaders.ContentType,
                        ContentType.Application.Json.toString(),
                    ),
                )
            },
        ),
    )
}
