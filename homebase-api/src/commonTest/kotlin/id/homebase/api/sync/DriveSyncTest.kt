package id.homebase.api.sync

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
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
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private const val CONVERSATION_FILE_TYPE = 8888

@OptIn(ExperimentalCoroutinesApi::class)
class DriveSyncTest {

    // All credentials in this test use a 16-byte zero shared secret; the MockEngine
    // decrypts captured request bodies with it to inspect the QueryBatchRequest.
    private val sharedSecret = ByteArray(16)

    private suspend fun buildSync(
        db: DatabaseManager,
        mockEngine: MockEngine,
        eventBus: EventBus,
        scope: kotlinx.coroutines.CoroutineScope,
        driveId: Uuid,
        policy: DriveSyncPolicy = DriveSyncPolicy(),
    ): DriveSync {
        val credentialsManager = CredentialsManager()
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("test.homebase.id"),
                clientAccessToken = "fake-token",
                sharedSecret = SecureByteArray(sharedSecret.copyOf())
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
            policy = policy,
        )
    }

    /** Decrypts a captured query-batch request body and parses it. */
    private suspend fun parseRequest(request: HttpRequestData): QueryBatchRequest {
        val envelope = request.body.toByteArray().decodeToString()
        val json = CryptoHelper.decryptContentAsString(envelope, sharedSecret)
        return OdinSystemSerializer.deserialize(json)
    }

    /** Plaintext query-batch response with no rows. Not an {iv,data} envelope, so the
     *  client returns it as-is without attempting decryption. */
    private fun emptyOkBody(): String =
        """{"name":null,"invalidDrive":false,"queryTime":0,"includeMetadataHeader":false,""" +
            """"cursorState":null,"searchResults":[],"hasMoreRows":false}"""

    /** Same, but advertises another page and echoes [cursorState] so the caller paginates. */
    private fun morePagesOkBody(cursorState: String): String {
        val escaped = cursorState.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"name":null,"invalidDrive":false,"queryTime":0,"includeMetadataHeader":false,""" +
            """"cursorState":"$escaped","searchResults":[],"hasMoreRows":true}"""
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

    private fun chatPolicy() = DriveSyncPolicy(
        fullSyncWindow = 60.days,
        initialQueries = listOf(FileQueryParams(fileType = listOf(CONVERSATION_FILE_TYPE))),
    )

    private fun isConversationQuery(req: QueryBatchRequest) =
        req.queryParams.fileType?.contains(CONVERSATION_FILE_TYPE) == true

    private fun isWindowedCrawl(req: QueryBatchRequest) = req.queryParams.fileType == null

    @Test
    fun freshSyncRunsInitialQueryBeforeWindowedCrawl() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val requests = mutableListOf<QueryBatchRequest>()
            val mockEngine = MockEngine { request ->
                requests.add(parseRequest(request))
                respond(emptyOkBody(), HttpStatusCode.OK)
            }
            val sync = buildSync(db, mockEngine, eventBus, backgroundScope, driveId, chatPolicy())

            val events = mutableListOf<BackendEvent>()
            val collector = launch { eventBus.events.collect { events.add(it) } }

            sync.sync()?.join()
            runCurrent()

            val firstConvIdx = requests.indexOfFirst { isConversationQuery(it) }
            val firstWindowIdx = requests.indexOfFirst { isWindowedCrawl(it) }
            assertTrue(firstConvIdx >= 0, "Expected a conversation-list (fileType=[8888]) query")
            assertTrue(firstWindowIdx >= 0, "Expected a windowed (no fileType) crawl")
            assertTrue(
                firstConvIdx < firstWindowIdx,
                "Conversation query must run BEFORE the windowed crawl (conv@$firstConvIdx, window@$firstWindowIdx)"
            )
            // The conversation query must not carry a cursor — it crawls from the start.
            assertEquals(null, requests[firstConvIdx].resultOptionsRequest.cursorState)
            assertTrue(
                events.any {
                    it is BackendEvent.DriveEvent.Stopped &&
                        it.result is BackendEvent.DriveResult.Completed
                },
                "Expected a single Completed stop"
            )
            collector.cancel()
        }
        db.close()
    }

    @Test
    fun freshSyncSeedsWindowCursorToSixtyDaysAgo() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val requests = mutableListOf<QueryBatchRequest>()
            val mockEngine = MockEngine { request ->
                requests.add(parseRequest(request))
                respond(emptyOkBody(), HttpStatusCode.OK)
            }
            val sync = buildSync(db, mockEngine, eventBus, backgroundScope, driveId, chatPolicy())

            sync.sync()?.join()
            runCurrent()

            val windowed = requests.firstOrNull { isWindowedCrawl(it) }
            assertNotNull(windowed, "Expected a windowed crawl request")
            val cursorState = windowed.resultOptionsRequest.cursorState
            assertNotNull(cursorState, "Windowed crawl must carry a seeded cursor")
            val pagingTime = QueryBatchCursor.fromJson(cursorState).paging?.time?.milliseconds
            assertNotNull(pagingTime, "Seeded cursor must have a paging time")
            val expected = UnixTimeUtc().addDays(-60).milliseconds
            // Wall-clock based; allow a few seconds of slack for test execution.
            assertTrue(
                kotlin.math.abs(pagingTime - expected) < 10_000,
                "Window floor should be ~60 days ago (got $pagingTime, expected ~$expected)"
            )
        }
        db.close()
    }

    @Test
    fun initialQueryPaginatesAndDoesNotLeakCursorIntoWindowedCrawl() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            // A cursor the conversation query "advances" to on page 1; the windowed
            // crawl must NOT resume from it — it must use the 60-day seed instead.
            val leakedCursor = QueryBatchCursor.fromStartPoint(UnixTimeUtc(123_456L)).toJson()
            val requests = mutableListOf<QueryBatchRequest>()
            val mockEngine = MockEngine { request ->
                val req = parseRequest(request)
                requests.add(req)
                when {
                    // Conversation query: first page advertises another page, second ends.
                    isConversationQuery(req) && req.resultOptionsRequest.cursorState == null ->
                        respond(morePagesOkBody(leakedCursor), HttpStatusCode.OK)
                    else -> respond(emptyOkBody(), HttpStatusCode.OK)
                }
            }
            val sync = buildSync(db, mockEngine, eventBus, backgroundScope, driveId, chatPolicy())

            sync.sync()?.join()
            runCurrent()

            val convRequests = requests.filter { isConversationQuery(it) }
            assertTrue(convRequests.size >= 2, "Conversation query should have paginated (>=2 requests)")
            assertEquals(
                leakedCursor,
                convRequests[1].resultOptionsRequest.cursorState,
                "Second conversation page should carry the echoed cursor"
            )
            // The windowed crawl must use the 60-day seed, NOT the conversation query's cursor.
            val windowed = requests.first { isWindowedCrawl(it) }
            val windowPaging = QueryBatchCursor.fromJson(windowed.resultOptionsRequest.cursorState!!)
                .paging!!.time.milliseconds
            assertTrue(
                windowPaging != 123_456L,
                "Windowed crawl leaked the conversation query's cursor"
            )
            val expected = UnixTimeUtc().addDays(-60).milliseconds
            assertTrue(
                kotlin.math.abs(windowPaging - expected) < 10_000,
                "Windowed crawl should use the 60-day seed"
            )
            // And nothing was persisted to the drive cursor (all responses were empty).
            assertEquals(null, CursorStorage(db, driveId).loadCursor())
        }
        db.close()
    }

    @Test
    fun incrementalSyncSkipsInitialQueryAndWindowSeed() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            // Pre-existing cursor => NOT a fresh sync.
            val savedCursor = QueryBatchCursor.fromStartPoint(UnixTimeUtc(999_999L))
            CursorStorage(db, driveId).saveCursor(savedCursor)

            val requests = mutableListOf<QueryBatchRequest>()
            val mockEngine = MockEngine { request ->
                requests.add(parseRequest(request))
                respond(emptyOkBody(), HttpStatusCode.OK)
            }
            val sync = buildSync(db, mockEngine, eventBus, backgroundScope, driveId, chatPolicy())

            sync.sync()?.join()
            runCurrent()

            assertFalse(
                requests.any { isConversationQuery(it) },
                "Incremental sync must not run the initial conversation query"
            )
            assertEquals(1, requests.size, "Incremental sync issues a single resume query")
            val pagingTime = QueryBatchCursor.fromJson(requests[0].resultOptionsRequest.cursorState!!)
                .paging!!.time.milliseconds
            assertEquals(
                999_999L, pagingTime,
                "Incremental sync must resume from the saved cursor, not a 60-day window"
            )
        }
        db.close()
    }

    @Test
    fun initialQueryFailureFallsThroughToWindowedCrawl() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val requests = mutableListOf<QueryBatchRequest>()
            val mockEngine = MockEngine { request ->
                val req = parseRequest(request)
                requests.add(req)
                if (isConversationQuery(req)) {
                    respond("boom", HttpStatusCode.InternalServerError)
                } else {
                    respond(emptyOkBody(), HttpStatusCode.OK)
                }
            }
            val sync = buildSync(db, mockEngine, eventBus, backgroundScope, driveId, chatPolicy())

            val events = mutableListOf<BackendEvent>()
            val collector = launch { eventBus.events.collect { events.add(it) } }

            sync.sync()?.join()
            runCurrent()

            assertTrue(requests.any { isConversationQuery(it) }, "Conversation query should have been attempted")
            assertTrue(requests.any { isWindowedCrawl(it) }, "Windowed crawl must still run after a failed initial query")
            assertTrue(
                events.any {
                    it is BackendEvent.DriveEvent.Stopped &&
                        it.result is BackendEvent.DriveResult.Completed
                },
                "Round should complete despite the initial-query failure"
            )
            assertFalse(
                events.any {
                    it is BackendEvent.DriveEvent.Stopped &&
                        it.result is BackendEvent.DriveResult.Aborted
                },
                "A best-effort initial-query failure must not abort the round"
            )
            collector.cancel()
        }
        db.close()
    }

    @Test
    fun noPolicyBehavesAsBefore() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val driveId = Uuid.random()
            val requests = mutableListOf<QueryBatchRequest>()
            val mockEngine = MockEngine { request ->
                requests.add(parseRequest(request))
                respond(emptyOkBody(), HttpStatusCode.OK)
            }
            // Default policy — no window, no initial query.
            val sync = buildSync(db, mockEngine, eventBus, backgroundScope, driveId)

            sync.sync()?.join()
            runCurrent()

            assertFalse(requests.any { isConversationQuery(it) }, "No policy => no conversation query")
            assertEquals(1, requests.size, "No policy => a single unfiltered crawl")
            assertEquals(
                null, requests[0].resultOptionsRequest.cursorState,
                "No policy => no window cursor seed (fresh sync starts at null)"
            )
        }
        db.close()
    }
}
