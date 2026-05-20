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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * End-to-end coverage for the bug the user reported in May 2026: chat rows with
 * corrupt server-side timestamps survived `logout()` and reappeared on next
 * login. We can't drive YouAuthFlowManager from a common test (it depends on
 * platform credential storage), but its load-bearing storage steps are
 * [DriveSyncManager.clearStorage] and the post-login [DriveSyncManager.start] /
 * [DriveSyncManager.mountDrive] dance. This test freezes the contract:
 *
 *  - Given a row in DriveMainIndex with a marker timestamp.
 *  - When clearStorage runs (the storage half of logout).
 *  - And the user "logs back in" (a fresh mountDrive + start cycle with a
 *    clean server returning no rows).
 *  - Then the marker timestamp does not exist anywhere in DriveMainIndex.
 *
 * A regression where, e.g., wipeAndRecreate gains a code path that resurrects
 * data (a future "smart wipe" that preserves "recently used" rows, an
 * in-memory cache that flushes back after the drop, a missing
 * `expectFreshCursors=true` after clearStorage) would fail this test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogoutLoginRoundTripTest {

    private val badUserDate = 9_999_999_999_999L // sentinel — far in the future

    @Test
    fun rowsPresentBeforeLogout_areGoneAfterLogoutAndCleanRelogin() {
        val db = DatabaseManager({ createInMemoryDatabase() })

        runTest {
            val credentials = buildCredentials()
            val eventBus = EventBus()
            // Mock engine that pretends the server is reachable but returns no
            // data — the post-login sync must produce a clean local state, not
            // re-deliver pre-wipe rows.
            val cleanServerEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{ "results": [], "cursor": null }"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json"),
                )
            }
            val manager = buildManagerWith(db, credentials, eventBus, backgroundScope, cleanServerEngine)

            val driveId = Uuid.random()
            val identityId = credentials.requireActiveCredentials().getIdentityId()

            // Seed: a single corrupt row representing the buggy server's delivery.
            seedBadRow(db, identityId, driveId)
            assertEquals(
                1L,
                countRowsWithUserDate(db, badUserDate),
                "precondition: seeded bad row must be present before logout",
            )

            // Storage half of logout. After this, the DB must be empty and the
            // expectFreshCursors flag should be set so a stale cursor cannot
            // silently resume a partial sync.
            manager.clearStorage()
            assertEquals(
                0L,
                countRowsWithUserDate(db, badUserDate),
                "logout's clearStorage must remove every DriveMainIndex row",
            )

            // "Login" half: a fresh mount + start with a clean server. If
            // anything in DriveSyncManager held the pre-wipe rows in memory and
            // wrote them back here, this assertion would fail.
            manager.mountDrive(driveId, "Chat")
            manager.start()
            runCurrent()
            advanceUntilIdle()

            assertEquals(
                0L,
                countRowsWithUserDate(db, badUserDate),
                "after a clean re-login, the pre-wipe bad row must not reappear",
            )
            // Belt-and-suspenders: the whole table must be empty.
            assertEquals(
                0L,
                countAllRows(db),
                "after a clean re-login with an empty server response, " +
                    "DriveMainIndex must remain empty",
            )
        }

        db.close()
    }

    private suspend fun seedBadRow(db: DatabaseManager, identityId: Uuid, driveId: Uuid) {
        db.withWrite { odin ->
            odin.driveMainIndexQueries.upsertDriveMainIndex(
                identityId = identityId,
                driveId = driveId,
                fileId = Uuid.random(),
                uniqueId = null,
                globalTransitId = null,
                groupId = null,
                senderId = null,
                originalAuthor = null,
                fileType = 7878L,
                dataType = 0L,
                archivalStatus = 0L,
                fileState = 1L,
                historyStatus = 0L,
                userDate = badUserDate,
                created = badUserDate,
                modified = badUserDate,
                fileSystemType = 0L,
                jsonHeader = "{}",
            )
        }
    }

    private suspend fun countRowsWithUserDate(db: DatabaseManager, userDate: Long): Long {
        // Use the DatabaseManager's read path so we go through the same
        // dispatcher as the production code — no risk of a race against the
        // background sync job. Raw SQL keeps the test independent of which
        // generated .sq query happens to match this shape.
        return db.executeReadQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM DriveMainIndex WHERE userDate = ?",
            mapper = { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 1,
            binders = { bindLong(0, userDate) },
        ).value
    }

    private suspend fun countAllRows(db: DatabaseManager): Long {
        return db.executeReadQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM DriveMainIndex",
            mapper = { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value
    }

    private fun buildManagerWith(
        db: DatabaseManager,
        credentialsManager: CredentialsManager,
        eventBus: EventBus,
        scope: CoroutineScope,
        engine: MockEngine,
        mandatoryDrives: Map<Uuid, String> = emptyMap(),
    ): DriveSyncManager {
        val httpClient = HttpClient(engine)
        val driveQueryProvider = DriveQueryProvider(httpClient, credentialsManager)
        return DriveSyncManager(
            driveQueryProvider = driveQueryProvider,
            credentialsManager = credentialsManager,
            eventBus = eventBus,
            scope = scope,
            databaseManager = db,
            mandatoryDrives = mandatoryDrives,
        )
    }

    private suspend fun buildCredentials(): CredentialsManager {
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
}
