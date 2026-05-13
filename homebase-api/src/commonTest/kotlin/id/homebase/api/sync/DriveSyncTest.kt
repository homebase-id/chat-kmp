package id.homebase.api.sync

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.createInMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class DriveSyncTest {

    private suspend fun buildSync(
        db: DatabaseManager,
        mockEngine: MockEngine,
        eventBus: EventBus,
        scope: kotlinx.coroutines.CoroutineScope,
        driveId: Uuid,
    ): DriveSync {
        val credentialsManager = CredentialsManager()
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("test.homebase.id"),
                clientAccessToken = "fake-token",
                sharedSecret = SecureByteArray(ByteArray(16))
            )
        )
        val httpClient = HttpClient(mockEngine)
        val driveQueryProvider = DriveQueryProvider(httpClient, credentialsManager)
        return DriveSync(
            identityId = credentialsManager.requireActiveCredentials().getIdentityId(),
            driveId = driveId,
            driveQueryProvider = driveQueryProvider,
            databaseManager = db,
            eventBus = eventBus,
            scope = scope,
        )
    }

    @Test
    fun forbiddenResponseEmitsPermissionDenied() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val mockEngine = MockEngine { respond("", HttpStatusCode.Forbidden) }
            val sync = buildSync(db, mockEngine, eventBus, backgroundScope, driveId)

            val emittedEvents = mutableListOf<BackendEvent>()
            val collector = launch { eventBus.events.collect { emittedEvents.add(it) } }

            sync.sync()?.join()
            runCurrent()

            assertTrue(
                emittedEvents.any {
                    it is BackendEvent.DriveEvent.Stopped &&
                        it.driveId == driveId &&
                        it.result is BackendEvent.DriveResult.PermissionDenied
                },
                "Expected PermissionDenied event"
            )
            assertFalse(
                emittedEvents.any {
                    it is BackendEvent.DriveEvent.Stopped &&
                        it.result is BackendEvent.DriveResult.Aborted
                },
                "Must not emit Failure for a 403"
            )
            collector.cancel()
        }
        db.close()
    }

    @Test
    fun forbiddenResponseDoesNotTriggerRetry() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val mockEngine = MockEngine { respond("", HttpStatusCode.Forbidden) }
            val sync = buildSync(db, mockEngine, eventBus, backgroundScope, driveId)

            val startedEvents = mutableListOf<BackendEvent>()
            val collector = launch { eventBus.events.collect { startedEvents.add(it) } }

            sync.sync()?.join()
            runCurrent()

            // Clear events collected during the initial sync
            val startedCountBefore = startedEvents.count { it is BackendEvent.DriveEvent.Started }

            // Advance past the retry window that Failure would trigger (1 second)
            advanceTimeBy(2000)
            runCurrent()

            val startedCountAfter = startedEvents.count { it is BackendEvent.DriveEvent.Started }
            // No new Started event — PermissionDenied must not schedule a retry
            assertFalse(
                startedCountAfter > startedCountBefore,
                "A retry sync must not be triggered after 403 Forbidden"
            )
            collector.cancel()
        }
        db.close()
    }
}
