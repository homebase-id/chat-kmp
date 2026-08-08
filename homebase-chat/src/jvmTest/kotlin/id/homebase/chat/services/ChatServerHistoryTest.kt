@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.services

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.CursorStorage
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.chatTargetDrive
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The windowed-sync server backfill (#1223): request shape, local upsert without touching
 * the drive sync cursor, and the per-session completeness flag.
 */
class ChatServerHistoryTest {

    private val sharedSecret = ByteArray(16)
    private val chatDriveId = chatTargetDrive.alias
    private val testIdentityId = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")

    private lateinit var dbm: DatabaseManager
    private lateinit var credentialsManager: CredentialsManager

    @BeforeTest
    fun setUp() = runTest {
        dbm = DatabaseManager({
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            OdinDatabase.Schema.create(driver)
            driver
        })
        credentialsManager = CredentialsManager().apply {
            setActiveCredentials(
                ApiCredentials.create(
                    domain = OdinId("owner.test"),
                    clientAccessToken = "test-token",
                    sharedSecret = SecureByteArray(sharedSecret.copyOf())
                )
            )
        }
    }

    @AfterTest
    fun tearDown() {
        dbm.close()
    }

    // ---------- helpers ----------

    private fun buildHistory(engine: MockEngine) = ChatServerHistory(
        credentialsManager = credentialsManager,
        dbm = dbm,
        driveQueryProvider = DriveQueryProvider(HttpClient(engine), credentialsManager),
    )

    private suspend fun parseRequest(request: HttpRequestData): QueryBatchRequest {
        val envelope = request.body.toByteArray().decodeToString()
        val json = CryptoHelper.decryptContentAsString(envelope, sharedSecret)
        return OdinSystemSerializer.deserialize(json)
    }

    /** A ServerFile as the query-batch wire returns it (unencrypted content). */
    private fun serverFileJson(
        conversationId: Uuid,
        userDateMs: Long,
        uniqueId: Uuid = Uuid.random(),
    ): String {
        val content = """{\"message\":\"msg\",\"deliveryStatus\":20,\"isEdited\":false,\"version\":1}"""
        return """{
            "fileId": "${Uuid.random()}",
            "driveId": "$chatDriveId",
            "fileState": "active",
            "fileSystemType": "standard",
            "sharedSecretEncryptedKeyHeader": {
                "encryptionVersion": 1,
                "iv": "AAAAAAAAAAAAAAAAAAAAAA==",
                "encryptedAesKey": "AAAAAAAAAAAAAAAAAAAAAA=="
            },
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": $userDateMs,
                "updated": $userDateMs,
                "isEncrypted": false,
                "senderOdinId": "owner.test",
                "originalAuthor": "owner.test",
                "appData": {
                    "uniqueId": "$uniqueId",
                    "fileType": ${ChatProtocol.MessageFileType},
                    "dataType": 0,
                    "groupId": "$conversationId",
                    "userDate": $userDateMs,
                    "content": "$content",
                    "archivalStatus": 0
                },
                "versionTag": "${Uuid.random()}",
                "payloads": []
            },
            "serverMetadata": {
                "accessControlList": {"requiredSecurityGroup": "owner"},
                "doNotIndex": false,
                "allowDistribution": true,
                "fileSystemType": "standard",
                "fileByteCount": 100,
                "originalRecipientCount": 0
            },
            "priority": 300,
            "fileByteCount": 100
        }"""
    }

    private fun responseBody(
        results: List<String>,
        hasMoreRows: Boolean,
        cursorState: String? = null,
    ): String {
        val cursor = cursorState?.let {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } ?: "null"
        return """{"name":null,"invalidDrive":false,"queryTime":0,"includeMetadataHeader":true,""" +
            """"cursorState":$cursor,"searchResults":[${results.joinToString(",")}],""" +
            """"hasMoreRows":$hasMoreRows}"""
    }

    private suspend fun localMessageCount(conversationId: Uuid): Int =
        QueryBatch(testIdentityId).queryBatchAsync(
            dbm = dbm,
            driveId = chatDriveId,
            noOfItems = 100,
            cursor = null,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
            fileState = FileStateFilter.All,
            filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
            groupIdAnyOf = listOf(conversationId),
        ).records.size

    // ---------- tests ----------

    @Test
    fun fetchOlderPage_requestShape() = runTest {
        val conversationId = Uuid.random()
        val anchor = 1_700_000_000_000L
        val engine = MockEngine { respond(responseBody(emptyList(), hasMoreRows = false), HttpStatusCode.OK) }

        buildHistory(engine).fetchOlderPage(conversationId, anchor)

        val request = engine.requestHistory.single()
        assertEquals("/api/v2/drives/$chatDriveId/files/query-batch", request.url.encodedPath)
        val parsed = parseRequest(request)
        assertEquals(listOf(ChatProtocol.MessageFileType), parsed.queryParams.fileType)
        assertEquals(listOf(conversationId), parsed.queryParams.groupId)
        assertEquals(ChatServerHistory.SERVER_HISTORY_PAGE_SIZE, parsed.resultOptionsRequest.maxRecords)
        assertEquals(QueryBatchSortOrder.NewestFirst, parsed.resultOptionsRequest.ordering)
        assertEquals(QueryBatchSortField.UserDate, parsed.resultOptionsRequest.sorting)
        val cursor = QueryBatchCursor.fromJson(assertNotNull(parsed.resultOptionsRequest.cursorState))
        assertEquals(anchor, cursor.paging?.time?.milliseconds)
        assertNull(cursor.paging?.row, "fromStartPoint anchors on time only; ties overlap by design")
    }

    @Test
    fun fetchOlderPage_emptyLocalWindowSendsNullCursor() = runTest {
        val engine = MockEngine { respond(responseBody(emptyList(), hasMoreRows = false), HttpStatusCode.OK) }

        buildHistory(engine).fetchOlderPage(Uuid.random(), oldestLocalSqlUserDateMs = null)

        assertNull(parseRequest(engine.requestHistory.single()).resultOptionsRequest.cursorState)
    }

    @Test
    fun fetchOlderPage_upsertsLocallyWithoutTouchingDriveCursor() = runTest {
        val conversationId = Uuid.random()
        val driveCursor = QueryBatchCursor.fromStartPoint(UnixTimeUtc(555_666L))
        CursorStorage(dbm, chatDriveId).saveCursor(driveCursor)

        val base = 1_600_000_000_000L
        val engine = MockEngine {
            respond(
                responseBody(
                    listOf(
                        serverFileJson(conversationId, base + 1000),
                        serverFileJson(conversationId, base + 2000),
                    ),
                    hasMoreRows = true,
                ),
                HttpStatusCode.OK
            )
        }
        val history = buildHistory(engine)

        val page = history.fetchOlderPage(conversationId, base + 10_000)

        assertEquals(2, page.upsertedCount)
        assertTrue(page.serverHasMore)
        assertTrue(history.mayHaveOlderHistory(conversationId))
        assertEquals(2, localMessageCount(conversationId), "Fetched headers must land in the local index")
        assertEquals(
            driveCursor.toJson(),
            assertNotNull(CursorStorage(dbm, chatDriveId).loadCursor()).toJson(),
            "The server backfill must NEVER advance the drive's persisted sync cursor"
        )
    }

    @Test
    fun fetchOlderPage_reUpsertOfKnownRowsIsHarmless() = runTest {
        val conversationId = Uuid.random()
        val base = 1_600_000_000_000L
        val fixedUnique = Uuid.random()
        val body = responseBody(
            listOf(serverFileJson(conversationId, base + 1000, uniqueId = fixedUnique)),
            hasMoreRows = false,
        )
        val engine = MockEngine { respond(body, HttpStatusCode.OK) }
        val history = buildHistory(engine)

        history.fetchOlderPage(conversationId, base + 10_000)
        history.reset() // allow the second probe
        history.fetchOlderPage(conversationId, base + 10_000)

        assertEquals(1, localMessageCount(conversationId), "Tied-boundary overlap must not duplicate rows")
    }

    @Test
    fun fetchOlderPage_lastPageMarksConversationComplete() = runTest {
        val conversationId = Uuid.random()
        val other = Uuid.random()
        val engine = MockEngine { respond(responseBody(emptyList(), hasMoreRows = false), HttpStatusCode.OK) }
        val history = buildHistory(engine)

        assertTrue(history.mayHaveOlderHistory(conversationId))
        val page = history.fetchOlderPage(conversationId, 1_700_000_000_000L)

        assertFalse(page.serverHasMore)
        assertEquals(0, page.upsertedCount)
        assertFalse(history.mayHaveOlderHistory(conversationId), "Empty page = server truly has no more")
        assertTrue(history.mayHaveOlderHistory(other), "Completeness is per conversation")

        history.reset()
        assertTrue(history.mayHaveOlderHistory(conversationId), "Logout reset clears the session flag")
    }
}
