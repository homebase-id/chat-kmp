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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class DriveSyncManagerTest {

    @Test
    fun startSetsInitialStateToInitialized() {
        val db = DatabaseManager({ createInMemoryDatabase() })

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

            val driveId = Uuid.random()
            val manager = DriveSyncManager(
                driveQueryProvider = driveQueryProvider,
                credentialsManager = credentialsManager,
                eventBus = EventBus(),
                scope = backgroundScope,
                databaseManager = db,
                mandatoryDrives = mapOf(driveId to "Test Drive"),
            )

            manager.start()

            assertEquals(DriveState.Initialized, manager.driveStatuses.value[driveId]?.state)
        }

        db.close()
    }

    @Test
    fun failedDriveAutoRetriesSyncAfterOneSecond() {
        val db = DatabaseManager({ createInMemoryDatabase() })

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

            val driveId = Uuid.random()
            val manager = DriveSyncManager(
                driveQueryProvider = driveQueryProvider,
                credentialsManager = credentialsManager,
                eventBus = eventBus,
                scope = backgroundScope,
                databaseManager = db,
                mandatoryDrives = mapOf(driveId to "Test Drive"),
            )

            manager.start()

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
        scope: CoroutineScope,
        mandatoryDrives: Map<Uuid, String> = emptyMap(),
    ): DriveSyncManager {
        val mockEngine = MockEngine { awaitCancellation() }
        val httpClient = HttpClient(mockEngine)
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

    @Test
    fun syncStateIsIdleBeforeStart() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val manager = buildManager(db, buildCredentials(), EventBus(), backgroundScope)
            assertEquals(SyncState.Idle, manager.syncState.value)
        }
        db.close()
    }

    @Test
    fun syncStateTransitionsToSyncingOnStartedEvent() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val manager = buildManager(db, buildCredentials(), eventBus, backgroundScope, mapOf(driveId to "Drive"))
            manager.start()
            runCurrent()

            eventBus.emit(BackendEvent.DriveEvent.Started(driveId))
            runCurrent()

            assertIs<SyncState.Syncing>(manager.syncState.value)
        }
        db.close()
    }

    @Test
    fun syncStateTransitionsToCompletedWhenAllDrivesComplete() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val manager = buildManager(db, buildCredentials(), eventBus, backgroundScope, mapOf(driveId to "Drive"))
            manager.start()
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
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val manager = buildManager(db, buildCredentials(), eventBus, backgroundScope, mapOf(driveId to "Drive"))
            manager.start()
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
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val manager = buildManager(db, buildCredentials(), eventBus, backgroundScope, mapOf(driveId to "Drive"))
            manager.start()
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
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val manager = buildManager(db, buildCredentials(), eventBus, backgroundScope, mapOf(driveId to "Drive"))
            manager.start()
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
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val manager = buildManager(db, buildCredentials(), eventBus, backgroundScope, mapOf(driveId to "Drive"))
            manager.start()
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
    fun permissionDeniedEventUnmountsDrive() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val manager = buildManager(db, buildCredentials(), eventBus, backgroundScope, mapOf(driveId to "Drive"))
            manager.start()
            runCurrent()

            eventBus.emit(BackendEvent.DriveEvent.Started(driveId))
            runCurrent()
            assertIs<SyncState.Syncing>(manager.syncState.value)

            eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId, 0, BackendEvent.DriveResult.PermissionDenied))
            runCurrent()

            // Drive removed — indicator must clear (Idle), not stay orange (Failed)
            assertTrue(manager.driveStatuses.value.isEmpty())
            assertEquals(SyncState.Idle, manager.syncState.value)

            // No retry after 1 second (unlike Failure)
            advanceTimeBy(1001)
            runCurrent()
            assertTrue(manager.driveStatuses.value.isEmpty())
        }
        db.close()
    }

    @Test
    fun permissionDeniedDoesNotMarkOtherDrivesAsFailed() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val completedDrive = Uuid.random()
            val deniedDrive = Uuid.random()
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope,
                mapOf(completedDrive to "OK Drive", deniedDrive to "Denied Drive")
            )
            manager.start()
            runCurrent()

            // First drive completes successfully
            eventBus.emit(BackendEvent.DriveEvent.Started(completedDrive))
            runCurrent()
            eventBus.emit(BackendEvent.DriveEvent.Stopped(completedDrive, 10, BackendEvent.DriveResult.Success))
            runCurrent()

            // Second drive is denied
            eventBus.emit(BackendEvent.DriveEvent.Started(deniedDrive))
            runCurrent()
            eventBus.emit(BackendEvent.DriveEvent.Stopped(deniedDrive, 0, BackendEvent.DriveResult.PermissionDenied))
            runCurrent()

            // Sync indicator must go green, not stay orange
            assertIs<SyncState.Completed>(manager.syncState.value)
            assertTrue(manager.driveStatuses.value.containsKey(completedDrive))
            assertFalse(manager.driveStatuses.value.containsKey(deniedDrive))
        }
        db.close()
    }

    @Test
    fun mountDriveAddsNewDrive() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val manager = buildManager(db, buildCredentials(), EventBus(), backgroundScope, emptyMap())
            manager.start()
            runCurrent()
            assertEquals(SyncState.Idle, manager.syncState.value)

            val driveId = Uuid.random()
            manager.mountDrive(driveId, "Extra Drive")
            runCurrent()

            assertTrue(manager.driveStatuses.value.containsKey(driveId))
        }
        db.close()
    }

    @Test
    fun mountDriveBeforeStartRegistersAndIsKickedBySyncAll() {
        // Bootstrap path: AuthConnectionCoordinator mounts add-on drives from the
        // registry BEFORE the WS handshake fires DriveSyncManager.start(). The mount
        // must register the drive in-memory, defer the sync kick, and the subsequent
        // syncAll() (the canonical "kick everything" call after start()) must include
        // the pre-registered drive.
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val manager = buildManager(db, buildCredentials(), EventBus(), backgroundScope, emptyMap())

            val driveId = Uuid.random()
            manager.mountDrive(driveId, "Pre-registered")
            runCurrent()

            // Registered but no sync kicked yet.
            assertTrue(manager.driveStatuses.value.containsKey(driveId))
            assertEquals(DriveState.Initialized, manager.driveStatuses.value[driveId]?.state)

            manager.start()
            runCurrent()
            // After start() alone, the pre-registered drive is still Initialized — the
            // kick is deferred until the caller's syncAll().
            assertEquals(DriveState.Initialized, manager.driveStatuses.value[driveId]?.state)

            // launch is required because syncAll() suspends on joinAll() against the
            // (hung) MockEngine and would block the test forever if invoked directly.
            backgroundScope.launch { manager.syncAll() }
            runCurrent()

            // performSync emits Started → state transitions to Synchronizing.
            assertIs<DriveState.Synchronizing>(manager.driveStatuses.value[driveId]?.state)
        }
        db.close()
    }

    @Test
    fun mountDriveWhilePausedRegistersAndIsKickedByNextSyncAll() {
        // Mid-session add-on install while the WS is paused (network blip / pre-reconnect):
        // the activation flow's mountDrive() must register, defer the kick, and let the
        // next reconnect's start() + syncAll() pick it up.
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val mandatoryDrive = Uuid.random()
            val manager = buildManager(
                db, buildCredentials(), EventBus(), backgroundScope,
                mapOf(mandatoryDrive to "Chat"),
            )
            manager.start()
            runCurrent()
            manager.pause()
            runCurrent()

            val addonDrive = Uuid.random()
            manager.mountDrive(addonDrive, "Mid-session add-on")
            runCurrent()

            // Registered while paused — no sync kicked yet.
            assertTrue(manager.driveStatuses.value.containsKey(addonDrive))
            assertEquals(DriveState.Initialized, manager.driveStatuses.value[addonDrive]?.state)

            manager.start()
            runCurrent()
            backgroundScope.launch { manager.syncAll() }
            runCurrent()

            assertIs<DriveState.Synchronizing>(manager.driveStatuses.value[addonDrive]?.state)
        }
        db.close()
    }

    @Test
    fun mountDriveWithoutCredentialsLogsAndSkips() {
        // Logout race during add-on activation: getActiveCredentials() returns null,
        // mountDrive must warn and bail without registering — no DriveSync object,
        // no entry in driveStatuses.
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val emptyCredentials = CredentialsManager() // never call setActiveCredentials
            val manager = buildManager(db, emptyCredentials, EventBus(), backgroundScope, emptyMap())

            val driveId = Uuid.random()
            manager.mountDrive(driveId, "No-creds drive")
            runCurrent()

            assertTrue(manager.driveStatuses.value.isEmpty())
        }
        db.close()
    }

    @Test
    fun unmountDriveRemovesDriveAndClearsStatus() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val manager = buildManager(db, buildCredentials(), eventBus, backgroundScope, mapOf(driveId to "Drive"))
            manager.start()
            runCurrent()

            manager.unmountDrive(driveId)
            runCurrent()

            assertTrue(manager.driveStatuses.value.isEmpty())
            assertEquals(SyncState.Idle, manager.syncState.value)
        }
        db.close()
    }

    @Test
    fun clearStorageCompletesEvenWhenCallerScopeIsCancelled() {
        // Use StandardTestDispatcher so all DB operations coordinate with virtual time
        val testDispatcher = StandardTestDispatcher()
        val db = DatabaseManager(
            driverProvider = { createInMemoryDatabase() },
            dispatcher = testDispatcher
        )

        runTest(testDispatcher) {
            val manager = buildManager(db, buildCredentials(), EventBus(), backgroundScope)

            // Seed each identity-scoped table so we can detect whether the wipe completed.
            val identityId = Uuid.random()
            db.keyValue.upsertValue(Uuid.random(), byteArrayOf(1, 2, 3))
            db.outbox.insert(
                driveId = Uuid.random(),
                uniqueId = Uuid.random(),
                dependencyUniqueId = null,
                priority = 0,
                uploadType = 0,
                json = byteArrayOf(0),
                filePaths = null,
            )
            db.appNotifications.insertNotification(
                identityId = identityId,
                notificationId = Uuid.random(),
                unread = 1,
                senderId = null,
                timestamp = 0,
                data = null,
                created = 0,
                modified = 0,
            )
            db.connectionCache.upsert(
                identityId = identityId,
                odinId = "frodo.shire.example",
                status = "connected",
                lastRefresh = 0,
            )

            // Sanity: every seed row is present before logout.
            assertEquals(1L, db.outbox.count())
            assertTrue(db.appNotifications.selectFirstPage(identityId, 10).isNotEmpty())
            assertTrue(db.connectionCache.selectByIdentity(identityId).isNotEmpty())

            // Simulate a caller (viewModelScope) that is cancelled while logout is running.
            // Without withContext(NonCancellable), clearStorage() would throw and leave
            // some of the identity-scoped tables populated.
            //
            // Run the caller on runTest's scheduler so advanceUntilIdle() actually drains
            // its work, and use CoroutineStart.ATOMIC so the launched body is guaranteed
            // to start executing before cancellation can take effect — i.e. it gets to
            // enter withContext(NonCancellable) on the first line of clearStorage(). With
            // the default DEFAULT start, dispatch races against cancel(): if cancel wins,
            // the body never runs and the wipe never happens, producing a flake.
            val callerJob = Job()
            val callerScope = CoroutineScope(callerJob + testDispatcher)
            val job = callerScope.launch { manager.clearStorage() }

            // Let the job start and enter withContext(NonCancellable)
            runCurrent()

            // NOW cancel the scope - the job is already protected by NonCancellable
            callerJob.cancel()

            // Process all pending work
            advanceUntilIdle()

            // Wait for the job to complete
            job.join()

            assertEquals(0L, db.outbox.count(), "Outbox should be wiped even if caller cancelled")
            assertTrue(
                db.appNotifications.selectFirstPage(identityId, 10).isEmpty(),
                "AppNotifications should be wiped even if caller cancelled"
            )
            assertTrue(
                db.connectionCache.selectByIdentity(identityId).isEmpty(),
                "ConnectionCache should be wiped even if caller cancelled"
            )
        }
        db.close()
    }

    @Test
    fun numberOfDrivesSyncingReturnsCorrectCount() {
        val db = DatabaseManager({ createInMemoryDatabase() })

        runTest() {
            val eventBus = EventBus()
            val driveId1 = Uuid.random()
            val driveId2 = Uuid.random()
            val manager = buildManager(db, buildCredentials(), eventBus, backgroundScope, mapOf(driveId1 to "Drive 1", driveId2 to "Drive 2"))
            manager.start()
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

    @Test
    fun ensureMandatoryMountedRegistersDrivesWithoutStarting() {
        // The bug from the second-login repro: mandatory drives were only mounted inside
        // start(), and start() only ran from the WS onConnected callback. If the handshake
        // didn't complete, the mandatory drives never showed up in driveStatuses.
        // ensureMandatoryMounted() decouples the mount from the running flag — drives must
        // appear as Initialized even without start() / syncAll() running.
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val driveA = Uuid.random()
            val driveB = Uuid.random()
            val manager = buildManager(
                db, buildCredentials(), EventBus(), backgroundScope,
                mandatoryDrives = mapOf(driveA to "Chat", driveB to "Contact"),
            )

            manager.ensureMandatoryMounted()
            runCurrent()

            assertEquals(DriveState.Initialized, manager.driveStatuses.value[driveA]?.state)
            assertEquals(DriveState.Initialized, manager.driveStatuses.value[driveB]?.state)
            assertFalse(manager.isRunning, "ensureMandatoryMounted must not flip isRunning")
        }
        db.close()
    }

    @Test
    fun mountDriveReturnsFalseOnSecondCall() {
        // The post-login WS-reconnect regression: VaultViewModel reactively called
        // AuthConnectionCoordinator.mountDrive(vault) the moment the vault drive appeared
        // in driveStatuses, while AuthConnectionCoordinator had ALREADY mounted it during
        // bootstrap. AuthCC.mountDrive needs to know "was anything actually new?" to skip
        // the refreshWsSubscription.trigger() that would tear down an in-flight WS
        // handshake. The return value of DriveSyncManager.mountDrive is that signal.
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val manager = buildManager(db, buildCredentials(), EventBus(), backgroundScope)
            val driveId = Uuid.random()

            val first = manager.mountDrive(driveId, "Vault")
            val second = manager.mountDrive(driveId, "Vault")

            assertTrue(first, "first mount must report newly-mounted")
            assertFalse(second, "second mount of same drive must report already-mounted")
            assertEquals(1, manager.driveStatuses.value.size)
        }
        db.close()
    }

    @Test
    fun ensureMandatoryMountedIsIdempotent() {
        // start() calls ensureMandatoryMounted() as a safety net. If AuthConnectionCoordinator
        // also calls it earlier (the new primary path), the second invocation from start()
        // must be a no-op rather than a failure or duplicate registration.
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val driveA = Uuid.random()
            val manager = buildManager(
                db, buildCredentials(), EventBus(), backgroundScope,
                mandatoryDrives = mapOf(driveA to "Chat"),
            )

            manager.ensureMandatoryMounted()
            runCurrent()
            manager.ensureMandatoryMounted()
            runCurrent()
            manager.start()
            runCurrent()

            assertEquals(1, manager.driveStatuses.value.size)
            assertEquals(DriveState.Initialized, manager.driveStatuses.value[driveA]?.state)
            assertTrue(manager.isRunning)
        }
        db.close()
    }

    @Test
    fun progressEventAdvancesSynchronizingCount() {
        // Drive.performSync emits Started → Progress(N) per batch upsert →
        // Stopped(Success, finalCount). DriveSyncManager translates that into
        // driveStatuses: Initialized → Synchronizing(count=0) → Synchronizing(count=N) →
        // Completed(totalCount=finalCount). The Synchronizing.count drives
        // LoginScreen's DriveProgressRow ("N records").
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope,
                mandatoryDrives = mapOf(driveId to "Chat"),
            )
            manager.start()
            runCurrent()

            // Synchronizing(count=0) after Started.
            eventBus.emit(BackendEvent.DriveEvent.Started(driveId))
            runCurrent()
            assertEquals(
                DriveState.Synchronizing(count = 0),
                manager.driveStatuses.value[driveId]?.state,
            )

            // First batch landed: count advances to 500.
            eventBus.emit(BackendEvent.DriveEvent.Progress(driveId, totalCount = 500))
            runCurrent()
            assertEquals(
                DriveState.Synchronizing(count = 500),
                manager.driveStatuses.value[driveId]?.state,
            )

            // Second batch: count advances to 1500.
            eventBus.emit(BackendEvent.DriveEvent.Progress(driveId, totalCount = 1500))
            runCurrent()
            assertEquals(
                DriveState.Synchronizing(count = 1500),
                manager.driveStatuses.value[driveId]?.state,
            )

            // Stopped(Success): transitions to Completed with the final total.
            eventBus.emit(
                BackendEvent.DriveEvent.Stopped(driveId, 1500, BackendEvent.DriveResult.Success)
            )
            runCurrent()
            assertEquals(
                DriveState.Completed(totalCount = 1500),
                manager.driveStatuses.value[driveId]?.state,
            )
        }
        db.close()
    }

    @Test
    fun progressEventIsIgnoredWhenCountWouldGoBackwards() {
        // A stray Progress event with totalCount < current.count must not snap the
        // progress bar backwards mid-sync. Real-world cause: late-arriving
        // out-of-order BatchReceived emits from optimistic writes had totalCount=1
        // pre-branch and would have undone real progress. Now the same guard
        // protects DriveEvent.Progress.
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope,
                mandatoryDrives = mapOf(driveId to "Chat"),
            )
            manager.start()
            runCurrent()

            eventBus.emit(BackendEvent.DriveEvent.Started(driveId))
            eventBus.emit(BackendEvent.DriveEvent.Progress(driveId, totalCount = 500))
            runCurrent()

            // Stray Progress with a smaller count — must not regress.
            eventBus.emit(BackendEvent.DriveEvent.Progress(driveId, totalCount = 1))
            runCurrent()
            assertEquals(
                DriveState.Synchronizing(count = 500),
                manager.driveStatuses.value[driveId]?.state,
            )
        }
        db.close()
    }
}
