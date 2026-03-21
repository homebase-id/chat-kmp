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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
