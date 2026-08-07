package id.homebase.api.sync

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.QueryBatchCollectionRequest
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResponse
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.CursorStorage
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.createInMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The syncAll() query-batch-collection wire-up (#1102): one batched page-1 call for the
 * own-host drives, each section routed back into its DriveSync as a prefetched first page,
 * with resumeBatchedRound(null) as the per-drive fallback for every degraded path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveSyncCollectionTest {

    private val sharedSecret = ByteArray(16)

    private suspend fun buildCredentials(): CredentialsManager {
        val credentialsManager = CredentialsManager()
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("test.homebase.id"),
                clientAccessToken = "fake-token",
                sharedSecret = SecureByteArray(sharedSecret.copyOf())
            )
        )
        return credentialsManager
    }

    private fun buildManager(
        db: DatabaseManager,
        credentialsManager: CredentialsManager,
        eventBus: EventBus,
        scope: CoroutineScope,
        mockEngine: MockEngine,
        mandatoryDrives: Map<Uuid, String>,
        collectionTail: List<Uuid> = emptyList(),
    ): DriveSyncManager {
        val driveQueryProvider = DriveQueryProvider(HttpClient(mockEngine), credentialsManager)
        return DriveSyncManager(
            driveQueryProvider = driveQueryProvider,
            credentialsManager = credentialsManager,
            eventBus = eventBus,
            scope = scope,
            databaseManager = db,
            mandatoryDrives = mandatoryDrives,
            collectionTail = collectionTail,
        )
    }

    private suspend fun parseCollectionRequest(request: HttpRequestData): QueryBatchCollectionRequest {
        val envelope = request.body.toByteArray().decodeToString()
        val json = CryptoHelper.decryptContentAsString(envelope, sharedSecret)
        return OdinSystemSerializer.deserialize(json)
    }

    private suspend fun parseQueryBatchRequest(request: HttpRequestData): QueryBatchRequest {
        val envelope = request.body.toByteArray().decodeToString()
        val json = CryptoHelper.decryptContentAsString(envelope, sharedSecret)
        return OdinSystemSerializer.deserialize(json)
    }

    private fun isCollectionPath(request: HttpRequestData) =
        request.url.encodedPath == "/api/v2/drives/query-batch-collection"

    private fun sectionJson(
        name: String,
        status: String = "ok",
        invalidDrive: Boolean = false,
        cursorState: String? = null,
        hasMoreRows: Boolean = false,
    ): String {
        val cursor = cursorState?.let {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } ?: "null"
        return """{"name":"$name","invalidDrive":$invalidDrive,"status":"$status","queryTime":0,""" +
            """"includeMetadataHeader":false,"cursorState":$cursor,"searchResults":[],""" +
            """"hasMoreRows":$hasMoreRows}"""
    }

    private fun collectionBody(vararg sections: String) =
        """{"results":[${sections.joinToString(",")}]}"""

    private fun emptyOkBody(cursorState: String? = null, hasMoreRows: Boolean = false): String {
        val cursor = cursorState?.let {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } ?: "null"
        return """{"name":null,"invalidDrive":false,"queryTime":0,"includeMetadataHeader":false,""" +
            """"cursorState":$cursor,"searchResults":[],"hasMoreRows":$hasMoreRows}"""
    }

    // ---------------------------------------------------------------------------------------------
    // Manager level
    // ---------------------------------------------------------------------------------------------

    @Test
    fun syncAllIssuesOneCollectionForOwnDrives() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val drives = List(3) { Uuid.random() }
            lateinit var engine: MockEngine
            engine = MockEngine { request ->
                if (isCollectionPath(request)) {
                    val req = parseCollectionRequest(request)
                    respond(
                        collectionBody(*req.queries.map { sectionJson(it.name) }.toTypedArray()),
                        HttpStatusCode.OK
                    )
                } else {
                    respond(emptyOkBody(), HttpStatusCode.OK)
                }
            }
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope, engine,
                mandatoryDrives = drives.associateWith { "Drive $it" },
            )
            val events = mutableListOf<BackendEvent>()
            val collector = launch { eventBus.events.collect { events.add(it) } }

            manager.start()
            runCurrent()
            manager.syncAll()
            runCurrent()

            assertEquals(1, engine.requestHistory.size, "3 own drives must produce exactly 1 request")
            val request = engine.requestHistory.single()
            assertTrue(isCollectionPath(request), "The single request must be the collection call")
            val parsed = parseCollectionRequest(request)
            assertEquals(drives.map { it.toString() }.toSet(), parsed.queries.map { it.name }.toSet())
            drives.forEach { driveId ->
                assertTrue(
                    events.any {
                        it is BackendEvent.DriveEvent.Stopped && it.driveId == driveId &&
                            it.result is BackendEvent.DriveResult.Completed
                    },
                    "Drive $driveId should complete from its collection section"
                )
            }
            collector.cancel()
        }
        db.close()
    }

    @Test
    fun budgetExhaustedSectionContinuesPerDrive() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val drives = List(3) { Uuid.random() }
            val starved = drives[2]
            // Seed a real cursor so the starved drive's submitted cursorState is non-trivial.
            val seeded = QueryBatchCursor.fromStartPoint(UnixTimeUtc(111_222L))
            CursorStorage(db, starved).saveCursor(seeded)

            lateinit var engine: MockEngine
            engine = MockEngine { request ->
                if (isCollectionPath(request)) {
                    val req = parseCollectionRequest(request)
                    respond(
                        collectionBody(
                            *req.queries.map { section ->
                                if (section.name == starved.toString()) {
                                    // Echo the submitted cursor verbatim, as the server does.
                                    sectionJson(
                                        section.name, status = "budgetExhausted",
                                        cursorState = section.resultOptionsRequest.cursorState,
                                        hasMoreRows = true,
                                    )
                                } else sectionJson(section.name)
                            }.toTypedArray()
                        ),
                        HttpStatusCode.OK
                    )
                } else {
                    respond(emptyOkBody(), HttpStatusCode.OK)
                }
            }
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope, engine,
                mandatoryDrives = drives.associateWith { "Drive $it" },
            )

            manager.start()
            runCurrent()
            manager.syncAll()
            runCurrent()

            val perDrive = engine.requestHistory.filterNot { isCollectionPath(it) }
            assertEquals(1, perDrive.size, "Only the budget-starved drive continues per-drive")
            assertTrue(
                perDrive.single().url.encodedPath.contains(starved.toString()),
                "The follow-up must target the starved drive"
            )
            assertEquals(
                seeded.toJson(),
                parseQueryBatchRequest(perDrive.single()).resultOptionsRequest.cursorState,
                "The per-drive continuation must carry the echoed (submitted) cursor"
            )
        }
        db.close()
    }

    @Test
    fun failedSectionFallsBackPerDriveAndKeepsUnmountSemantics() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val drives = List(3) { Uuid.random() }
            val revoked = drives[1]
            lateinit var engine: MockEngine
            engine = MockEngine { request ->
                when {
                    isCollectionPath(request) -> {
                        val req = parseCollectionRequest(request)
                        respond(
                            collectionBody(
                                *req.queries.map { section ->
                                    if (section.name == revoked.toString()) {
                                        sectionJson(section.name, status = "noReadGrant", invalidDrive = true)
                                    } else sectionJson(section.name)
                                }.toTypedArray()
                            ),
                            HttpStatusCode.OK
                        )
                    }
                    // The per-drive fallback confirms the revocation through the existing 403 path.
                    request.url.encodedPath.contains(revoked.toString()) ->
                        respond("", HttpStatusCode.Forbidden)
                    else -> respond(emptyOkBody(), HttpStatusCode.OK)
                }
            }
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope, engine,
                mandatoryDrives = drives.associateWith { "Drive $it" },
            )
            val events = mutableListOf<BackendEvent>()
            val collector = launch { eventBus.events.collect { events.add(it) } }

            manager.start()
            runCurrent()
            manager.syncAll()
            runCurrent()

            assertTrue(
                events.any {
                    it is BackendEvent.DriveEvent.Stopped && it.driveId == revoked &&
                        it.result is BackendEvent.DriveResult.PermissionDenied
                },
                "The revoked drive must go through the existing 403 → PermissionDenied path"
            )
            // The eventBus collector unmounts on PermissionDenied — same as a per-drive 403 today.
            runCurrent()
            assertFalse(
                manager.driveStatuses.value.containsKey(revoked),
                "PermissionDenied must unmount the drive for the session"
            )
            // The healthy drives completed from their sections, no per-drive calls for them.
            val perDrive = engine.requestHistory.filterNot { isCollectionPath(it) }
            assertEquals(1, perDrive.size, "Only the failed section falls back per-drive")
            collector.cancel()
        }
        db.close()
    }

    @Test
    fun collectionHttpFailureFallsBackPerDrive() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val drives = List(3) { Uuid.random() }
            lateinit var engine: MockEngine
            engine = MockEngine { request ->
                if (isCollectionPath(request)) {
                    respond("boom", HttpStatusCode.InternalServerError)
                } else {
                    respond(emptyOkBody(), HttpStatusCode.OK)
                }
            }
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope, engine,
                mandatoryDrives = drives.associateWith { "Drive $it" },
            )
            val events = mutableListOf<BackendEvent>()
            val collector = launch { eventBus.events.collect { events.add(it) } }

            manager.start()
            runCurrent()
            manager.syncAll()
            runCurrent()

            val perDrive = engine.requestHistory.filterNot { isCollectionPath(it) }
            assertEquals(3, perDrive.size, "Every drive must fall back to its own per-drive round")
            drives.forEach { driveId ->
                assertEquals(
                    1,
                    events.count { it is BackendEvent.DriveEvent.Started && it.driveId == driveId },
                    "Exactly one round per drive despite the failed collection"
                )
                assertTrue(
                    events.any {
                        it is BackendEvent.DriveEvent.Stopped && it.driveId == driveId &&
                            it.result is BackendEvent.DriveResult.Completed
                    },
                    "Drive $driveId must complete via the fallback"
                )
            }
            collector.cancel()
        }
        db.close()
    }

    @Test
    fun missingSectionFallsBackPerDrive() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val drives = List(3) { Uuid.random() }
            val dropped = drives[0]
            lateinit var engine: MockEngine
            engine = MockEngine { request ->
                if (isCollectionPath(request)) {
                    val req = parseCollectionRequest(request)
                    respond(
                        collectionBody(
                            *req.queries.filter { it.name != dropped.toString() }
                                .map { sectionJson(it.name) }.toTypedArray()
                        ),
                        HttpStatusCode.OK
                    )
                } else {
                    respond(emptyOkBody(), HttpStatusCode.OK)
                }
            }
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope, engine,
                mandatoryDrives = drives.associateWith { "Drive $it" },
            )
            val events = mutableListOf<BackendEvent>()
            val collector = launch { eventBus.events.collect { events.add(it) } }

            manager.start()
            runCurrent()
            manager.syncAll()
            runCurrent()

            val perDrive = engine.requestHistory.filterNot { isCollectionPath(it) }
            assertEquals(1, perDrive.size, "A dropped section must NOT read as 'no changes' — it re-queries per-drive")
            assertTrue(perDrive.single().url.encodedPath.contains(dropped.toString()))
            drives.forEach { driveId ->
                assertTrue(
                    events.any {
                        it is BackendEvent.DriveEvent.Stopped && it.driveId == driveId &&
                            it.result is BackendEvent.DriveResult.Completed
                    },
                    "Drive $driveId must still complete"
                )
            }
            collector.cancel()
        }
        db.close()
    }

    @Test
    fun remoteDriveExcludedFromCollection() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val ownDrives = List(2) { Uuid.random() }
            val remoteDrive = Uuid.random()
            lateinit var engine: MockEngine
            engine = MockEngine { request ->
                if (isCollectionPath(request)) {
                    val req = parseCollectionRequest(request)
                    respond(
                        collectionBody(*req.queries.map { sectionJson(it.name) }.toTypedArray()),
                        HttpStatusCode.OK
                    )
                } else {
                    respond(emptyOkBody(), HttpStatusCode.OK)
                }
            }
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope, engine,
                mandatoryDrives = ownDrives.associateWith { "Own $it" },
            )
            // Mount the peer drive before start() so it isn't kicked at mount time.
            manager.mountDrive(remoteDrive, "Community", ownerOdinId = OdinId("peer.example.com"))
            manager.start()
            runCurrent()
            manager.syncAll()
            runCurrent()

            val collection = engine.requestHistory.single { isCollectionPath(it) }
            val parsed = parseCollectionRequest(collection)
            assertFalse(
                parsed.queries.any { it.name == remoteDrive.toString() },
                "A peer-hosted drive must not be a section of the own-host collection"
            )
            val peerRequests = engine.requestHistory.filter { it.url.encodedPath.startsWith("/api/v2/peer/") }
            assertEquals(1, peerRequests.size, "The remote drive must still sync over the peer path")
            assertTrue(peerRequests.single().url.encodedPath.contains(remoteDrive.toString()))
        }
        db.close()
    }

    @Test
    fun singleEligibleDriveSkipsCollection() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            lateinit var engine: MockEngine
            engine = MockEngine { respond(emptyOkBody(), HttpStatusCode.OK) }
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope, engine,
                mandatoryDrives = mapOf(driveId to "Only Drive"),
            )

            manager.start()
            runCurrent()
            manager.syncAll()
            runCurrent()

            assertEquals(1, engine.requestHistory.size)
            assertFalse(
                isCollectionPath(engine.requestHistory.single()),
                "A collection of one is a query-batch with extra wrapping — go straight per-drive"
            )
        }
        db.close()
    }

    @Test
    fun busyDriveGetsKillroyNotSection() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val drives = List(3) { Uuid.random() }
            val busy = drives[2]
            lateinit var engine: MockEngine
            engine = MockEngine { request ->
                when {
                    isCollectionPath(request) -> {
                        val req = parseCollectionRequest(request)
                        respond(
                            collectionBody(*req.queries.map { sectionJson(it.name) }.toTypedArray()),
                            HttpStatusCode.OK
                        )
                    }
                    // The busy drive's in-flight per-drive round never returns.
                    request.url.encodedPath.contains(busy.toString()) -> awaitCancellation()
                    else -> respond(emptyOkBody(), HttpStatusCode.OK)
                }
            }
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope, engine,
                mandatoryDrives = drives.associateWith { "Drive $it" },
            )

            manager.start()
            runCurrent()
            manager.syncDrive(busy) // takes the busy drive's sync lock, hangs on HTTP
            runCurrent()
            manager.syncAll()
            runCurrent()

            val collection = engine.requestHistory.single { isCollectionPath(it) }
            val parsed = parseCollectionRequest(collection)
            assertEquals(2, parsed.queries.size, "The locked drive must not be a section")
            assertFalse(parsed.queries.any { it.name == busy.toString() })
        }
        db.close()
    }

    @Test
    fun collectionTailDrivesOrderedLast() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val chatLike = Uuid.random()
            val feedLike = Uuid.random()
            val small = List(2) { Uuid.random() }
            // Mount order puts the tail drives FIRST to prove the ordering is applied, not incidental.
            val mounts = linkedMapOf(
                chatLike to "Chat", feedLike to "Feed",
                small[0] to "Contacts", small[1] to "Profile",
            )
            lateinit var engine: MockEngine
            engine = MockEngine { request ->
                if (isCollectionPath(request)) {
                    val req = parseCollectionRequest(request)
                    respond(
                        collectionBody(*req.queries.map { sectionJson(it.name) }.toTypedArray()),
                        HttpStatusCode.OK
                    )
                } else respond(emptyOkBody(), HttpStatusCode.OK)
            }
            val manager = buildManager(
                db, buildCredentials(), eventBus, backgroundScope, engine,
                mandatoryDrives = mounts,
                collectionTail = listOf(feedLike, chatLike), // chat expected largest → very last
            )

            manager.start()
            runCurrent()
            manager.syncAll()
            runCurrent()

            val parsed = parseCollectionRequest(engine.requestHistory.single { isCollectionPath(it) })
            val names = parsed.queries.map { it.name }
            assertEquals(
                listOf(small[0].toString(), small[1].toString(), feedLike.toString(), chatLike.toString()),
                names,
                "Small drives first (mount order), then the tail in its given order, chat very last"
            )
        }
        db.close()
    }

    // ---------------------------------------------------------------------------------------------
    // DriveSync level
    // ---------------------------------------------------------------------------------------------

    private suspend fun buildSync(
        db: DatabaseManager,
        mockEngine: MockEngine,
        eventBus: EventBus,
        scope: CoroutineScope,
        driveId: Uuid,
    ): DriveSync {
        val credentialsManager = buildCredentials()
        val driveQueryProvider = DriveQueryProvider(HttpClient(mockEngine), credentialsManager)
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
    fun prefetchedPageConsumedWithoutHttpAndAdvancesCursor() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val engine = MockEngine { respond(emptyOkBody(), HttpStatusCode.OK) }
            val sync = buildSync(db, engine, eventBus, backgroundScope, driveId)
            val prefetchCursor = QueryBatchCursor.fromStartPoint(UnixTimeUtc(42_000L)).toJson()

            val round = sync.beginBatchedRound()
            assertNotNull(round, "Idle drive must hand out a batched round")
            assertNotNull(round.request, "Default policy must be prefetchable")
            sync.resumeBatchedRound(
                round,
                QueryBatchResponse(cursorState = prefetchCursor, hasMoreRows = false)
            ).join()
            runCurrent()

            assertEquals(0, engine.requestHistory.size, "A done prefetched page must cost zero HTTP calls")

            // The next round proves the in-memory cursor advanced to the prefetched cursorState.
            sync.sync()?.join()
            runCurrent()
            assertEquals(1, engine.requestHistory.size)
            assertEquals(
                prefetchCursor,
                parseQueryBatchRequest(engine.requestHistory.single()).resultOptionsRequest.cursorState,
            )
        }
        db.close()
    }

    @Test
    fun prefetchedPageWithMoreRowsPaginatesPerDrive() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val engine = MockEngine { respond(emptyOkBody(), HttpStatusCode.OK) }
            val sync = buildSync(db, engine, eventBus, backgroundScope, driveId)
            val echoedCursor = QueryBatchCursor.fromStartPoint(UnixTimeUtc(77_000L)).toJson()

            val round = sync.beginBatchedRound()!!
            sync.resumeBatchedRound(
                round,
                // What a budgetExhausted section looks like after toQueryBatchResponse():
                // no rows, the submitted cursor echoed, more rows advertised.
                QueryBatchResponse(cursorState = echoedCursor, hasMoreRows = true)
            ).join()
            runCurrent()

            assertEquals(1, engine.requestHistory.size, "hasMoreRows must continue per-drive")
            assertEquals(
                echoedCursor,
                parseQueryBatchRequest(engine.requestHistory.single()).resultOptionsRequest.cursorState,
                "Page 2 must resume from the prefetched page's cursor"
            )
        }
        db.close()
    }

    @Test
    fun killroySetDuringBatchedRoundTriggersResync() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val engine = MockEngine { respond(emptyOkBody(), HttpStatusCode.OK) }
            val sync = buildSync(db, engine, eventBus, backgroundScope, driveId)

            val events = mutableListOf<BackendEvent>()
            val collector = launch { eventBus.events.collect { events.add(it) } }
            runCurrent() // subscribe the collector before begin emits Started

            val round = sync.beginBatchedRound()!!
            // A WS change lands mid-collection: sync() finds the lock held and flags killroy.
            assertEquals(null, sync.sync(), "sync() during a batched round must coalesce, not run")
            sync.resumeBatchedRound(
                round,
                QueryBatchResponse(cursorState = null, hasMoreRows = false)
            ).join()
            // The killroy re-sync is a NEW job launched from the batched round's tail. Its HTTP
            // hops to a real dispatcher, so assert on the Started event it emits on the test
            // scheduler at performSync entry — the deterministic proof a follow-up round ran.
            advanceUntilIdle()

            assertEquals(
                2,
                events.count { it is BackendEvent.DriveEvent.Started && it.driveId == driveId },
                "The change signalled during the collection round trip must trigger a follow-up round"
            )
            collector.cancel()
        }
        db.close()
    }
}
