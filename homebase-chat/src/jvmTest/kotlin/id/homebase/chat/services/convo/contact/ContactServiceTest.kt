package id.homebase.chat.services.convo.contact

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactName
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.contacts.ContactsProvider
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.chat.data.ContactUiModel
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
import kotlin.test.assertNotNull

class ContactServiceTest {

    private val saved = OdinId("saved.homebase.id")
    private val unsaved = OdinId("agnes.dominion.id")
    private val blocked = OdinId("blocked.homebase.id")

    @Test
    fun connectedIdentityWithNoSavedContactIsListed() = withService { service ->
        val contacts = awaitMerged(service)

        val row = assertNotNull(contacts.singleOrNull { it.odinId == unsaved })
        assertEquals("agnes.dominion.id", row.name)
        assertEquals(ContactConnectionState.Connected, row.connectionState)
        assertEquals(ContactConnectionState.Connected, service.resolveByOdinId(unsaved).connectionState)
    }

    @Test
    fun unionSkipsBlockedAndSavedIdentities() = withService { service ->
        val contacts = awaitMerged(service)

        assertEquals(setOf(saved, unsaved), contacts.map { it.odinId }.toSet())
        assertEquals("Sam", contacts.single { it.odinId == saved }.name)
    }

    @Test
    fun savedContactIdentitiesExcludeUnsavedConnections() = withService { service ->
        awaitMerged(service)

        assertEquals(setOf(saved), service.savedContactIdentities.value)
    }

    // Saved row enriched as Connected means the repository and the connection map have both landed.
    private suspend fun awaitMerged(service: ContactService): List<ContactUiModel> =
        withTimeout(10_000) {
            service.contacts.first { list ->
                list.any { it.odinId == saved && it.connectionState == ContactConnectionState.Connected }
            }
        }

    private fun withService(block: suspend (ContactService) -> Unit) = runBlocking {
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
            val eventBus = EventBus()
            // Connection endpoints fail so the cache-hydrated map below stays authoritative.
            val http = HttpClient(MockEngine { req ->
                if (req.url.encodedPath.contains("/connections")) {
                    respondError(HttpStatusCode.InternalServerError)
                } else {
                    respond(
                        """{"uniqueId":"11111111-1111-1111-1111-111111111111","versionTag":"22222222-2222-2222-2222-222222222222"}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            })
            val cache = ConnectionCacheRepository(dbm, credentialsManager)
            cache.persistConnections(connected = listOf(saved, unsaved), blocked = listOf(blocked))

            val repo = ContactRepository(
                contactsProvider = ContactsProvider(http, credentialsManager) { _, _ -> null },
                contactPayloadReader = { _, _, _ -> null },
                databaseManager = dbm,
                credentialsManager = credentialsManager,
                eventBus = eventBus,
                scope = scope,
            )
            assertNotNull(repo.save(ContactContent(odinId = "Saved.Homebase.Id", name = ContactName(displayName = "Sam"))))

            val service = ContactService(
                contactRepository = repo,
                connections = ConnectionService(
                    provider = ConnectionNetworkProvider(http, credentialsManager),
                    eventBus = eventBus,
                    scope = scope,
                    cache = cache,
                ),
                scope = scope,
            )
            service.start()
            block(service)
        } finally {
            scope.cancel()
        }
    }
}
