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
            eventBus.emit(BackendEvent.DriveEvent.Failed(driveId, "simulated network error"))
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
}
