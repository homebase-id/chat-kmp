package id.homebase.api.sync

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.createInMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import id.homebase.api.client.eventbus.BackendEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class DriveSyncManagerTest {

    @Test
    fun startSetsInitialStateToInitialized() {
        val db = DatabaseManager { createInMemoryDatabase() }

        runTest {
            val credentialsManager = CredentialsManager()
            credentialsManager.setActiveCredentials(
                ApiCredentials.create(
                    domain = OdinId("test.homebase.id"),
                    clientAccessToken = "fake-token",
                    sharedSecret = SecureByteArray(ByteArray(16))
                )
            )

            val mockEngine = MockEngine { respond("", HttpStatusCode.OK) }
            val httpClient = HttpClient(mockEngine)
            val driveQueryProvider = DriveQueryProvider(httpClient, credentialsManager)

            val manager = DriveSyncManager(
                driveQueryProvider = driveQueryProvider,
                credentialsManager = credentialsManager,
                eventBus = EventBus(),
                scope = this,
                databaseManager = db
            )

            val driveId = Uuid.random()
            manager.start(mapOf(driveId to "Test Drive"))

            assertEquals(DriveState.Initialized, manager.driveStatuses.value[driveId]?.state)
        }

        db.close()
    }

    @Test
    fun failedDriveAutoRetriesSyncAfterOneSecond() {
        val db = DatabaseManager { createInMemoryDatabase() }

        runTest {
            val credentialsManager = CredentialsManager()
            credentialsManager.setActiveCredentials(
                ApiCredentials.create(
                    domain = OdinId("test.homebase.id"),
                    clientAccessToken = "fake-token",
                    sharedSecret = SecureByteArray(ByteArray(16))
                )
            )

            // Mock that never responds — keeps sync in Synchronizing state so we can assert cleanly
            val mockEngine = MockEngine { awaitCancellation() }
            val httpClient = HttpClient(mockEngine)
            val driveQueryProvider = DriveQueryProvider(httpClient, credentialsManager)
            val eventBus = EventBus()

            val manager = DriveSyncManager(
                driveQueryProvider = driveQueryProvider,
                credentialsManager = credentialsManager,
                eventBus = eventBus,
                scope = this,
                databaseManager = db
            )

            val driveId = Uuid.random()
            manager.start(mapOf(driveId to "Test Drive"))

            // Emit a Failed event directly (simulating what DriveSync emits after a real failure)
            eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId, 0, BackendEvent.DriveResult.Failure("simulated network error")))
            advanceTimeBy(1)

            assertEquals(
                DriveState.Failed("simulated network error"),
                manager.driveStatuses.value[driveId]?.state
            )

            // Advance past the 1-second retry delay and drain pending coroutines
            advanceTimeBy(1000)
            runCurrent()

            // sync() was called — performSync() emits DriveEvent.Started, transitioning to Synchronizing
            assertIs<DriveState.Synchronizing>(manager.driveStatuses.value[driveId]?.state)
        }

        db.close()
    }

    private fun buildManager(
        db: DatabaseManager,
        credentialsManager: CredentialsManager,
        eventBus: EventBus,
        scope: kotlinx.coroutines.CoroutineScope
    ): DriveSyncManager {
        val mockEngine = MockEngine { awaitCancellation() }
        val httpClient = HttpClient(mockEngine)
        val driveQueryProvider = DriveQueryProvider(httpClient, credentialsManager)
        return DriveSyncManager(
            driveQueryProvider = driveQueryProvider,
            credentialsManager = credentialsManager,
            eventBus = eventBus,
            scope = scope,
            databaseManager = db
        )
    }

    private fun buildCredentials(): CredentialsManager {
        val credentialsManager = CredentialsManager()
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("test.homebase.id"),
                clientAccessToken = "fake-token",
                sharedSecret = SecureByteArray(ByteArray(16))
            )
        )
        return credentialsManager
    }

    @Test
    fun syncStateIsIdleBeforeStart() {
        val db = DatabaseManager { createInMemoryDatabase() }
        runTest {
            val manager = buildManager(db, buildCredentials(), EventBus(), this)
            assertEquals(SyncState.Idle, manager.syncState.value)
        }
        db.close()
    }

    @Test
    fun syncStateTransitionsToSyncingOnStartedEvent() {
        val db = DatabaseManager { createInMemoryDatabase() }
        runTest {
            val eventBus = EventBus()
            val manager = buildManager(db, buildCredentials(), eventBus, this)
            val driveId = Uuid.random()
            manager.start(mapOf(driveId to "Drive"))
            runCurrent()

            eventBus.emit(BackendEvent.DriveEvent.Started(driveId))
            runCurrent()

            assertIs<SyncState.Syncing>(manager.syncState.value)
        }
        db.close()
    }

    @Test
    fun syncStateTransitionsToCompletedWhenAllDrivesComplete() {
        val db = DatabaseManager { createInMemoryDatabase() }
        runTest {
            val eventBus = EventBus()
            val manager = buildManager(db, buildCredentials(), eventBus, this)
            val driveId = Uuid.random()
            manager.start(mapOf(driveId to "Drive"))
            runCurrent()

            eventBus.emit(BackendEvent.DriveEvent.Started(driveId))
            runCurrent()
            eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId, 5, BackendEvent.DriveResult.Success))
            runCurrent()

            assertIs<SyncState.Completed>(manager.syncState.value)
        }
        db.close()
    }

    @Test
    fun syncStateTransitionsToFailedOnFailedEvent() {
        val db = DatabaseManager { createInMemoryDatabase() }
        runTest {
            val eventBus = EventBus()
            val manager = buildManager(db, buildCredentials(), eventBus, this)
            val driveId = Uuid.random()
            manager.start(mapOf(driveId to "Drive"))
            runCurrent()

            eventBus.emit(BackendEvent.DriveEvent.Started(driveId))
            runCurrent()
            eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId, 0, BackendEvent.DriveResult.Failure("error")))
            advanceTimeBy(1)

            assertIs<SyncState.Failed>(manager.syncState.value)
        }
        db.close()
    }

    @Test
    fun syncAllStartedEventFiredOnTransitionToSyncing() {
        val db = DatabaseManager { createInMemoryDatabase() }
        runTest {
            val eventBus = EventBus()
            val manager = buildManager(db, buildCredentials(), eventBus, this)
            val driveId = Uuid.random()
            manager.start(mapOf(driveId to "Drive"))
            runCurrent()

            val emittedEvents = mutableListOf<BackendEvent>()
            val job = launch { eventBus.events.collect { emittedEvents.add(it) } }

            eventBus.emit(BackendEvent.DriveEvent.Started(driveId))
            runCurrent()

            assertTrue(emittedEvents.any { it is BackendEvent.SyncAllStarted })
            job.cancel()
        }
        db.close()
    }

    @Test
    fun syncAllCompletedEventFiredOnTransitionToCompleted() {
        val db = DatabaseManager { createInMemoryDatabase() }
        runTest {
            val eventBus = EventBus()
            val manager = buildManager(db, buildCredentials(), eventBus, this)
            val driveId = Uuid.random()
            manager.start(mapOf(driveId to "Drive"))
            runCurrent()

            eventBus.emit(BackendEvent.DriveEvent.Started(driveId))
            runCurrent()

            val emittedEvents = mutableListOf<BackendEvent>()
            val job = launch { eventBus.events.collect { emittedEvents.add(it) } }

            eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId, 3, BackendEvent.DriveResult.Success))
            runCurrent()

            assertTrue(emittedEvents.any { it is BackendEvent.SyncAllStopped && it.result is BackendEvent.SyncAllResult.Success })
            job.cancel()
        }
        db.close()
    }

    @Test
    fun syncAllFailedEventFiredOnTransitionToFailed() {
        val db = DatabaseManager { createInMemoryDatabase() }
        runTest {
            val eventBus = EventBus()
            val manager = buildManager(db, buildCredentials(), eventBus, this)
            val driveId = Uuid.random()
            manager.start(mapOf(driveId to "Drive"))
            runCurrent()

            eventBus.emit(BackendEvent.DriveEvent.Started(driveId))
            runCurrent()

            val emittedEvents = mutableListOf<BackendEvent>()
            val job = launch { eventBus.events.collect { emittedEvents.add(it) } }

            eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId, 0, BackendEvent.DriveResult.Failure("network error")))
            advanceTimeBy(1)

            assertTrue(emittedEvents.any { it is BackendEvent.SyncAllStopped && it.result is BackendEvent.SyncAllResult.Failure })
            job.cancel()
        }
        db.close()
    }

    @Test
    fun numberOfDrivesSyncingReturnsCorrectCount() {
        val db = DatabaseManager { createInMemoryDatabase() }
        runTest {
            val eventBus = EventBus()
            val manager = buildManager(db, buildCredentials(), eventBus, this)
            val driveId1 = Uuid.random()
            val driveId2 = Uuid.random()
            manager.start(mapOf(driveId1 to "Drive 1", driveId2 to "Drive 2"))
            runCurrent()

            assertEquals(0, manager.numberOfDrivesSyncing())

            eventBus.emit(BackendEvent.DriveEvent.Started(driveId1))
            runCurrent()
            assertEquals(1, manager.numberOfDrivesSyncing())

            eventBus.emit(BackendEvent.DriveEvent.Started(driveId2))
            runCurrent()
            assertEquals(2, manager.numberOfDrivesSyncing())

            eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId1, 0, BackendEvent.DriveResult.Success))
            runCurrent()
            assertEquals(1, manager.numberOfDrivesSyncing())
        }
        db.close()
    }
}
