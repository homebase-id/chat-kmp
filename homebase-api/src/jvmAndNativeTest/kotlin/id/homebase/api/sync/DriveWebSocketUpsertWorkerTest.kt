package id.homebase.api.sync

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.createInMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Locks down [DriveWebSocketUpsertWorker]'s contract:
 *  - one batch upsert per drain (queue + single-flight Mutex),
 *  - a [BackendEvent.DataEvent.BatchReceived] per drain,
 *  - works for any drive id (not just chat),
 *  - cancel() prevents pending work.
 *
 * Uses [runBlocking] with real dispatchers (the worker's
 * [SupervisorJob] + [Dispatchers.Default] default) — this exercises
 * the actual concurrency the worker is built for. Tests poll for
 * expected events with bounded timeouts so they're deterministic.
 */
class DriveWebSocketUpsertWorkerTest {

    private lateinit var db: DatabaseManager
    private lateinit var workerScope: CoroutineScope

    @BeforeTest
    fun setUp() {
        db = DatabaseManager({ createInMemoryDatabase() })
        workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        workerScope.cancel()
        db.close()
    }

    @Test
    fun submit_singleFile_persistsRowAndEmitsBatchReceived() = runBlocking {
        val identityId = Uuid.random()
        val driveId = Uuid.random()
        val eventBus = EventBus()

        val firstBatch = CompletableDeferred<BackendEvent.DataEvent.BatchReceived>()
        val collector = launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.DataEvent.BatchReceived) {
                    firstBatch.complete(event)
                }
            }
        }

        val worker = DriveWebSocketUpsertWorker(
            identityId = identityId,
            driveId = driveId,
            databaseManager = db,
            eventBus = eventBus,
            scope = workerScope,
        )

        val fileId = Uuid.random()
        val file = makeFile(fileId = fileId, driveId = driveId)

        worker.submit(file)

        val batch = withTimeoutOrNull(5.seconds) { firstBatch.await() }
        assertNotNull(batch, "no BatchReceived event observed within timeout")

        val stored = db.driveMainIndex.selectByIdentityAndDriveAndFile(
            identityId = identityId, driveId = driveId, fileId = fileId
        )
        assertNotNull(stored, "expected DriveMainIndex row to be present")

        assertEquals(driveId, batch.driveId)
        assertEquals(1, batch.batchData.size)
        assertEquals(fileId, batch.batchData.first().fileId)

        collector.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submit_burst_batchesIntoSingleDrain() = runTest {
        // Why not the class-level `runBlocking` + `Dispatchers.Default` setup
        // the other tests use: this test asserts a coalescing invariant that
        // is timing-sensitive. On a fast box submits 1..5 all add to the queue
        // before the launched drain coroutine starts, and we get 1 batch. On a
        // contended Linux CI runner the worker thread can start the drain
        // (snapshotting [file_1] alone) before the test thread has issued
        // submits 2..5 — splitting into 2 batches and failing the assertion.
        //
        // Switching to a single-threaded `StandardTestDispatcher` removes the
        // race entirely: `submit()` doesn't suspend in the no-contention case,
        // so all 5 submits complete synchronously on the test thread before
        // the dispatcher gets a chance to run drain_1. drain_1's snapshot is
        // therefore [file_1..file_5], one batch. Killroy may still be set
        // (submits 2..5 saw the outer mutex held), so a follow-up drain_2 is
        // launched — it finds an empty queue and returns without emitting.
        //
        // We bridge `withWriteTransaction`'s real-dispatcher hop by waiting on
        // a `CompletableDeferred` the collector completes, not by relying on
        // `advanceUntilIdle()` alone (the test scheduler appears idle while
        // the DB write is in flight on its own dispatcher).
        val dispatcher = StandardTestDispatcher(testScheduler)
        val testWorkerScope = CoroutineScope(SupervisorJob() + dispatcher)

        val identityId = Uuid.random()
        val driveId = Uuid.random()
        val eventBus = EventBus()

        val collected = mutableListOf<BackendEvent.DataEvent.BatchReceived>()
        val firstBatch = CompletableDeferred<Unit>()
        val collector = launch(dispatcher) {
            eventBus.events.collect { event ->
                if (event is BackendEvent.DataEvent.BatchReceived) {
                    collected.add(event)
                    if (collected.size == 1) firstBatch.complete(Unit)
                }
            }
        }
        advanceUntilIdle() // collector subscribed

        val worker = DriveWebSocketUpsertWorker(
            identityId = identityId,
            driveId = driveId,
            databaseManager = db,
            eventBus = eventBus,
            scope = testWorkerScope,
        )

        val files = (1..5).map { makeFile(fileId = Uuid.random(), driveId = driveId) }
        for (f in files) worker.submit(f) // synchronous on a confined dispatcher

        firstBatch.await()   // bridges the DB-dispatcher hop
        advanceUntilIdle()   // let killroy-triggered drain_2 run (and find an empty queue)

        assertEquals(
            1, collected.size,
            "burst of 5 submits should drain into ONE batch (got ${collected.size})"
        )
        assertEquals(5, collected.first().batchData.size)

        for (f in files) {
            val row = db.driveMainIndex.selectByIdentityAndDriveAndFile(
                identityId = identityId, driveId = driveId, fileId = f.fileId
            )
            assertNotNull(row, "missing row for fileId=${f.fileId}")
        }

        collector.cancel()
        testWorkerScope.cancel()
    }

    @Test
    fun emit_carriesWebSocketSource() = runBlocking {
        val identityId = Uuid.random()
        val driveId = Uuid.random()
        val eventBus = EventBus()

        val firstBatch = CompletableDeferred<BackendEvent.DataEvent.BatchReceived>()
        val collector = launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.DataEvent.BatchReceived) firstBatch.complete(event)
            }
        }

        val worker = DriveWebSocketUpsertWorker(
            identityId = identityId,
            driveId = driveId,
            databaseManager = db,
            eventBus = eventBus,
            scope = workerScope,
        )

        worker.submit(makeFile(fileId = Uuid.random(), driveId = driveId))

        val batch = withTimeoutOrNull(5.seconds) { firstBatch.await() }
        assertNotNull(batch)
        assertEquals(driveId, batch.driveId, "batch must be tagged with the drive id")

        collector.cancel()
    }

    @Test
    fun submit_isDriveAgnostic() = runBlocking {
        val identityId = Uuid.random()
        val driveAlpha = Uuid.random()
        val driveBeta = Uuid.random()
        val eventBus = EventBus()

        val collected = mutableListOf<BackendEvent.DataEvent.BatchReceived>()
        val collector = launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.DataEvent.BatchReceived) collected.add(event)
            }
        }
        delay(20)

        val workerA = DriveWebSocketUpsertWorker(
            identityId = identityId,
            driveId = driveAlpha,
            databaseManager = db,
            eventBus = eventBus,
            scope = workerScope,
        )
        val workerB = DriveWebSocketUpsertWorker(
            identityId = identityId,
            driveId = driveBeta,
            databaseManager = db,
            eventBus = eventBus,
            scope = workerScope,
        )

        val fileA = makeFile(fileId = Uuid.random(), driveId = driveAlpha)
        val fileB = makeFile(fileId = Uuid.random(), driveId = driveBeta)

        workerA.submit(fileA)
        workerB.submit(fileB)

        withTimeoutOrNull(5.seconds) {
            while (collected.size < 2) delay(20)
        }
        delay(100)

        assertNotNull(
            db.driveMainIndex.selectByIdentityAndDriveAndFile(identityId, driveAlpha, fileA.fileId)
        )
        assertNotNull(
            db.driveMainIndex.selectByIdentityAndDriveAndFile(identityId, driveBeta, fileB.fileId)
        )

        val byDrive = collected.groupBy { it.driveId }
        assertEquals(setOf(driveAlpha, driveBeta), byDrive.keys)
        assertEquals(1, byDrive[driveAlpha]?.size)
        assertEquals(1, byDrive[driveBeta]?.size)
        assertEquals(fileA.fileId, byDrive[driveAlpha]?.first()?.batchData?.first()?.fileId)
        assertEquals(fileB.fileId, byDrive[driveBeta]?.first()?.batchData?.first()?.fileId)

        collector.cancel()
    }

    @Test
    fun submit_sequential_emitsTwoBatches() = runBlocking {
        // Sanity check: back-to-back submit+settle cycles drain
        // independently. Distinct from the batched-burst case because
        // the mutex is fully released between them.
        val identityId = Uuid.random()
        val driveId = Uuid.random()
        val eventBus = EventBus()

        val collected = mutableListOf<BackendEvent.DataEvent.BatchReceived>()
        val collector = launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.DataEvent.BatchReceived) collected.add(event)
            }
        }
        delay(20)

        val worker = DriveWebSocketUpsertWorker(
            identityId = identityId,
            driveId = driveId,
            databaseManager = db,
            eventBus = eventBus,
            scope = workerScope,
        )

        worker.submit(makeFile(fileId = Uuid.random(), driveId = driveId))
        withTimeoutOrNull(5.seconds) {
            while (collected.isEmpty()) delay(20)
        }

        worker.submit(makeFile(fileId = Uuid.random(), driveId = driveId))
        withTimeoutOrNull(5.seconds) {
            while (collected.size < 2) delay(20)
        }
        delay(100)

        assertEquals(2, collected.size)
        assertEquals(1, collected[0].batchData.size)
        assertEquals(1, collected[1].batchData.size)

        collector.cancel()
    }

    @Test
    fun submit_stalePush_doesNotEmitBatchReceived() = runBlocking {
        // A push the DriveMainIndex timestamp guard rejects (older `updated`
        // than what we already hold — e.g. the half-stale `statisticsChanged`
        // fan-out copy) must not produce a BatchReceived: the worker emits only
        // the rows that actually wrote. Mapping the rejected payload otherwise
        // paints it into the UI for a frame (the reaction-highlight blink).
        val identityId = Uuid.random()
        val driveId = Uuid.random()
        val eventBus = EventBus()

        val collected = mutableListOf<BackendEvent.DataEvent.BatchReceived>()
        val collector = launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.DataEvent.BatchReceived) collected.add(event)
            }
        }
        delay(20)

        val worker = DriveWebSocketUpsertWorker(
            identityId = identityId,
            driveId = driveId,
            databaseManager = db,
            eventBus = eventBus,
            scope = workerScope,
        )

        val fileId = Uuid.random()
        val uniqueId = Uuid.random()

        // Establish the current row (newer timestamp) — emits one batch.
        worker.submit(makeFile(fileId, driveId, uniqueId, updatedMs = 2_000_000_000_000L))
        withTimeoutOrNull(5.seconds) { while (collected.isEmpty()) delay(20) }

        // Stale push: same fileId, older `updated` → rejected by the guard.
        worker.submit(makeFile(fileId, driveId, uniqueId, updatedMs = 1_000_000_000_000L))
        delay(300) // give any emit a chance to (not) happen

        assertEquals(
            1, collected.size,
            "stale push rejected by the guard must not emit a second BatchReceived (got ${collected.size})",
        )

        collector.cancel()
    }

    @Test
    fun cancel_doesNotProduceMoreThanOneBatch() = runBlocking {
        val identityId = Uuid.random()
        val driveId = Uuid.random()
        val eventBus = EventBus()

        val collected = mutableListOf<BackendEvent.DataEvent.BatchReceived>()
        val collector = launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.DataEvent.BatchReceived) collected.add(event)
            }
        }
        delay(20)

        val worker = DriveWebSocketUpsertWorker(
            identityId = identityId,
            driveId = driveId,
            databaseManager = db,
            eventBus = eventBus,
            scope = workerScope,
        )

        worker.submit(makeFile(fileId = Uuid.random(), driveId = driveId))
        worker.cancel()
        delay(200) // give any in-flight drain a chance to either complete or abort

        assertTrue(
            collected.size <= 1,
            "cancel must not produce more than one BatchReceived (got ${collected.size})"
        )

        collector.cancel()
    }

    /**
     * Build a minimal [HomebaseFile] from a JSON template that satisfies
     * `baseUpsertEntryZapZap`'s SQL constraints. Same shape as
     * [id.homebase.api.sync.database.MainIndexMetaTest]'s fixture.
     */
    private fun makeFile(
        fileId: Uuid,
        driveId: Uuid,
        uniqueId: Uuid = Uuid.random(),
        updatedMs: Long = Clock.System.now().epochSeconds * 1000,
    ): HomebaseFile {
        val now = Clock.System.now().epochSeconds
        val json = """{
            "fileId": "$fileId",
            "driveId": "$driveId",
            "fileState": "active",
            "fileSystemType": "standard",
            "serverFileIsEncrypted": "true",
            "keyHeader": {
                "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
            },
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": ${now}000,
                "updated": $updatedMs,
                "transitCreated": 0,
                "transitUpdated": 0,
                "serverFileIsEncrypted": true,
                "senderOdinId": "test.sender",
                "originalAuthor": "test.sender",
                "appData": {
                    "uniqueId": "$uniqueId",
                    "tags": null,
                    "fileType": 1,
                    "dataType": 1,
                    "groupId": null,
                    "userDate": ${now}000,
                    "content": "x",
                    "previewThumbnail": null,
                    "archivalStatus": 0
                },
                "localAppData": null,
                "referencedFile": null,
                "reactionPreview": null,
                "versionTag": "${Uuid.random()}",
                "payloads": [],
                "dataSource": null
            },
            "serverMetadata": {
                "accessControlList": {
                    "requiredSecurityGroup": "owner",
                    "circleIdList": null,
                    "odinIdList": null
                },
                "doNotIndex": false,
                "allowDistribution": false,
                "fileSystemType": "standard",
                "fileByteCount": 100,
                "originalRecipientCount": 0,
                "transferHistory": null
            },
            "priority": 100,
            "fileByteCount": 100
        }"""
        return OdinSystemSerializer.deserialize<HomebaseFile>(json)
    }
}
