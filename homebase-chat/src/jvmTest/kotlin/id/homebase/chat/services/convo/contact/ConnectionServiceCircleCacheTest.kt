package id.homebase.chat.services.convo.contact

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionServiceCircleCacheTest {

    private val circleId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val sam = OdinId("sam.homebase.id")
    private val kim = OdinId("kim.homebase.id")

    private fun circle(vararg members: OdinId) = CircleWithMembers(
        circle = RedactedCircleDefinition(id = circleId, name = "Locate"),
        members = members.toList(),
    )

    @Test
    fun offlineColdStartShowsCachedCirclesAsLoaded() = withHarness { h ->
        h.cache.persistCircles(listOf(circle(sam)))

        val service = h.service(online = null)
        service.start()
        val state = withTimeout(10_000) { service.circles.first { it.isLoaded } }

        assertEquals(setOf("sam.homebase.id"), state.membersOf(circleId))
    }

    @Test
    fun coldStartWithNoCacheStaysNotLoaded() = withHarness { h ->
        val service = h.service(online = null)
        service.start()
        service.refresh()

        assertFalse(service.circles.value.isLoaded)
    }

    @Test
    fun successfulFetchOverwritesCacheAndNextColdStartSeesIt() = withHarness { h ->
        h.cache.persistCircles(listOf(circle(sam)))

        h.service(online = """[{"circle":{"id":"$circleId","name":"Locate"},"members":["kim.homebase.id"]}]""")
            .refresh()

        val coldStart = h.service(online = null)
        coldStart.start()
        val state = withTimeout(10_000) { coldStart.circles.first { it.isLoaded } }
        assertEquals(setOf(kim.domainName), state.membersOf(circleId))
    }

    @Test
    fun emptySuccessfulFetchReplacesCachedCircles() = withHarness { h ->
        h.cache.persistCircles(listOf(circle(sam)))

        h.service(online = "[]").refresh()

        assertEquals(emptyList(), h.cache.hydrateCircles())
        val coldStart = h.service(online = null)
        coldStart.start()
        val state = withTimeout(10_000) { coldStart.circles.first { it.isLoaded } }
        assertTrue(state.circles.isEmpty())
    }

    @Test
    fun logoutWipeClearsCircleCache() = withHarness { h ->
        h.cache.persistCircles(listOf(circle(sam)))

        h.dbm.wipeAndRecreate()

        assertNull(h.cache.hydrateCircles())
    }

    private class Harness(
        val dbm: DatabaseManager,
        val cache: ConnectionCacheRepository,
        private val credentialsManager: CredentialsManager,
        private val scope: CoroutineScope,
    ) {
        /** [online] is the circles response body; null makes every connections call fail. */
        fun service(online: String?): ConnectionService {
            val http = HttpClient(MockEngine { req ->
                val json = headersOf("Content-Type", ContentType.Application.Json.toString())
                when {
                    online == null -> respondError(HttpStatusCode.InternalServerError)
                    req.url.encodedPath.endsWith("/circles/with-members") ->
                        respond(online, HttpStatusCode.OK, json)
                    else -> respond("""{"results":[]}""", HttpStatusCode.OK, json)
                }
            })
            return ConnectionService(
                provider = ConnectionNetworkProvider(http, credentialsManager),
                eventBus = EventBus(),
                scope = scope,
                cache = cache,
            )
        }
    }

    private fun withHarness(block: suspend (Harness) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OdinDatabase.Schema.create(it) }
            val dbm = DatabaseManager({ driver })
            val credentialsManager = CredentialsManager().apply {
                setActiveCredentials(
                    ApiCredentials.create(
                        domain = OdinId("self.homebase.id"),
                        clientAccessToken = "test-token",
                        sharedSecret = SecureByteArray(ByteArray(16)),
                    )
                )
            }
            block(Harness(dbm, ConnectionCacheRepository(dbm, credentialsManager), credentialsManager, scope))
        } finally {
            scope.cancel()
        }
    }
}
