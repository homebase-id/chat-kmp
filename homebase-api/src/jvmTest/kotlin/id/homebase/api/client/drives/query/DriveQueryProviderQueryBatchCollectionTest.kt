package id.homebase.api.client.drives.query

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.CollectionQueryParamSection
import id.homebase.api.client.drives.CollectionSectionResultOptions
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.QueryBatchCollectionRequest
import id.homebase.api.client.drives.QueryBatchSectionStatus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * [DriveQueryProvider.queryBatchCollection] — POST /drives/query-batch-collection. Pins the
 * request shape (no driveId path segment; each section's driveId rides in the body instead)
 * and the per-section decode: a healthy section's rows go through the same salvage-decode as
 * [DriveQueryProvider.queryBatch], and a failed section comes back with a status rather than
 * failing the whole collection.
 */
class DriveQueryProviderQueryBatchCollectionTest {

    // MockEngine decrypts the captured request body with this shared secret to inspect the
    // QueryBatchCollectionRequest actually sent over the wire.
    private val sharedSecret = ByteArray(16)

    private suspend fun buildProvider(mockEngine: MockEngine): DriveQueryProvider {
        val credentialsManager = CredentialsManager()
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("test.homebase.id"),
                clientAccessToken = "fake-token",
                sharedSecret = SecureByteArray(sharedSecret.copyOf())
            )
        )
        return DriveQueryProvider(HttpClient(mockEngine), credentialsManager)
    }

    private suspend fun parseRequest(request: HttpRequestData): QueryBatchCollectionRequest {
        val envelope = request.body.toByteArray().decodeToString()
        val json = CryptoHelper.decryptContentAsString(envelope, sharedSecret)
        return OdinSystemSerializer.deserialize(json)
    }

    private suspend fun rawRequestJson(request: HttpRequestData): String {
        val envelope = request.body.toByteArray().decodeToString()
        return CryptoHelper.decryptContentAsString(envelope, sharedSecret)
    }

    /** Minimal ServerFile shape that decodes cleanly (mirrors ContactFixtures' fixture). */
    private fun serverFileJson(fileId: Uuid, driveId: Uuid): String = """
        {
          "fileId": "$fileId",
          "driveId": "$driveId",
          "fileState": "active",
          "fileSystemType": "standard",
          "sharedSecretEncryptedKeyHeader": { "encryptionVersion": 1, "iv": "AAAA", "encryptedAesKey": "AAAA" },
          "fileMetadata": {
            "versionTag": "11111111-1111-1111-1111-111111111111",
            "appData": { "uniqueId": "$fileId", "fileType": 100 }
          },
          "serverMetadata": {}
        }
    """.trimIndent()

    private fun section(name: String, driveId: Uuid, fileType: Int, cursor: String? = null) =
        CollectionQueryParamSection(
            name = name,
            driveId = driveId,
            queryParams = FileQueryParams(fileType = listOf(fileType)),
            resultOptionsRequest = CollectionSectionResultOptions(cursorState = cursor),
        )

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(body.trimIndent(), HttpStatusCode.OK)

    @Test
    fun decodesEachNamedSectionIndependently() = runTest {
        val photosDriveId = Uuid.random()
        val docsDriveId = Uuid.random()
        val fileId = Uuid.random()

        lateinit var capturedRequest: HttpRequestData
        val mockEngine = MockEngine { request ->
            capturedRequest = request
            respondJson(
                """
                {
                  "results": [
                    {
                      "name": "photos",
                      "invalidDrive": false,
                      "status": "ok",
                      "queryTime": 0,
                      "includeMetadataHeader": false,
                      "cursorState": "next-page-cursor",
                      "hasMoreRows": true,
                      "searchResults": [${serverFileJson(fileId, photosDriveId)}]
                    },
                    {
                      "name": "docs",
                      "invalidDrive": true,
                      "status": "noReadGrant",
                      "errorMessage": "Unauthorized to read to drive",
                      "queryTime": 0,
                      "includeMetadataHeader": false,
                      "cursorState": null,
                      "hasMoreRows": false,
                      "searchResults": []
                    }
                  ]
                }
                """
            )
        }

        val provider = buildProvider(mockEngine)
        val request = QueryBatchCollectionRequest(
            queries = listOf(
                section("photos", photosDriveId, fileType = 100),
                section("docs", docsDriveId, fileType = 200),
            ),
            maxRecords = 500,
        )

        val response = provider.queryBatchCollection(request)

        // Request shape: no driveId path segment — each section's driveId rides in the body.
        assertEquals("/api/v2/drives/query-batch-collection", capturedRequest.url.encodedPath)
        val sentBody = parseRequest(capturedRequest)
        assertEquals(listOf("photos", "docs"), sentBody.queries.map { it.name })
        assertEquals(photosDriveId, sentBody.queries[0].driveId)
        assertEquals(500, sentBody.maxRecords)

        assertEquals(2, response.results.size)

        val photos = response.results.first { it.name == "photos" }
        assertEquals(QueryBatchSectionStatus.Ok, photos.status)
        assertFalse(photos.isFailure)
        assertEquals("next-page-cursor", photos.cursorState)
        assertTrue(photos.hasMoreRows)
        assertEquals(1, photos.searchResults.size)
        assertEquals(fileId, photos.searchResults[0].fileId)

        val docs = response.results.first { it.name == "docs" }
        assertEquals(QueryBatchSectionStatus.NoReadGrant, docs.status)
        assertTrue(docs.isFailure)
        assertTrue(docs.invalidDrive)
        assertTrue(docs.searchResults.isEmpty())
    }

    @Test
    fun budgetIsSentPerRequestAndSectionOptionsCarryNoMaxRecords() = runTest {
        // The budget is one number for the whole call. A per-section maxRecords would be ignored by
        // the server, so the section options type must not offer one to send.
        lateinit var capturedRequest: HttpRequestData
        val mockEngine = MockEngine { request ->
            capturedRequest = request
            respondJson("""{ "results": [] }""")
        }

        val provider = buildProvider(mockEngine)
        provider.queryBatchCollection(
            QueryBatchCollectionRequest(
                queries = listOf(section("a", Uuid.random(), fileType = 100)),
                maxRecords = 250,
            )
        )

        val json = Json.parseToJsonElement(rawRequestJson(capturedRequest)).jsonObject
        assertEquals("250", json["maxRecords"].toString())

        val sectionJson = json["queries"]!!.toString()
        assertFalse(
            sectionJson.contains("maxRecords"),
            "section options must not send maxRecords: $sectionJson",
        )
    }

    @Test
    fun perSectionFileSystemTypeIsSentWhenSet() = runTest {
        lateinit var capturedRequest: HttpRequestData
        val mockEngine = MockEngine { request ->
            capturedRequest = request
            respondJson("""{ "results": [] }""")
        }

        val provider = buildProvider(mockEngine)
        provider.queryBatchCollection(
            QueryBatchCollectionRequest(
                queries = listOf(
                    section("standard", Uuid.random(), fileType = 100),
                    section("comments", Uuid.random(), fileType = 100)
                        .copy(fileSystemType = FileSystemType.Comment),
                ),
                maxRecords = 100,
            )
        )

        val sent = parseRequest(capturedRequest)
        assertNull(sent.queries[0].fileSystemType)
        assertEquals(FileSystemType.Comment, sent.queries[1].fileSystemType)
    }

    @Test
    fun budgetExhaustedSectionEchoesItsCursorAndCarriesNoRows() = runTest {
        // The cursor on a skipped section is the one we submitted; it goes back out next round, so
        // losing it here would silently lose or replay records.
        val mockEngine = MockEngine {
            respondJson(
                """
                {
                  "results": [
                    {
                      "name": "later",
                      "invalidDrive": false,
                      "status": "budgetExhausted",
                      "cursorState": "submitted-cursor",
                      "hasMoreRows": true,
                      "searchResults": []
                    }
                  ]
                }
                """
            )
        }

        val provider = buildProvider(mockEngine)
        val response = provider.queryBatchCollection(
            QueryBatchCollectionRequest(
                queries = listOf(section("later", Uuid.random(), fileType = 100, cursor = "submitted-cursor")),
                maxRecords = 10,
            )
        )

        val skipped = response.results.single()
        assertEquals(QueryBatchSectionStatus.BudgetExhausted, skipped.status)
        assertFalse(skipped.isFailure, "budget exhaustion is not a failure — the section just wasn't reached")
        assertTrue(skipped.needsAnotherRound)
        assertEquals("submitted-cursor", skipped.cursorState)
        assertTrue(skipped.searchResults.isEmpty())
    }

    @Test
    fun unknownStatusDecodesToNullRatherThanThrowing() = runTest {
        // A newer server may add a status this build doesn't know. coerceInputValues turns it into
        // null instead of throwing, so one unrecognised value can't fail the whole sync.
        val mockEngine = MockEngine {
            respondJson(
                """
                {
                  "results": [
                    {
                      "name": "future",
                      "invalidDrive": true,
                      "status": "somethingNewFromTheFuture",
                      "hasMoreRows": false,
                      "searchResults": []
                    }
                  ]
                }
                """
            )
        }

        val provider = buildProvider(mockEngine)
        val response = provider.queryBatchCollection(
            QueryBatchCollectionRequest(
                queries = listOf(section("future", Uuid.random(), fileType = 100)),
                maxRecords = 100,
            )
        )

        val section = response.results.single()
        assertNull(section.status)
        assertTrue(section.isFailure, "with no usable status, invalidDrive still marks it as failed")
    }

    @Test
    fun sectionWithoutStatusFallsBackToInvalidDrive() = runTest {
        // A server predating per-section status sends neither status nor errorCode.
        val mockEngine = MockEngine {
            respondJson(
                """
                {
                  "results": [
                    { "name": "legacy", "invalidDrive": true, "hasMoreRows": false, "searchResults": [] }
                  ]
                }
                """
            )
        }

        val provider = buildProvider(mockEngine)
        val response = provider.queryBatchCollection(
            QueryBatchCollectionRequest(
                queries = listOf(section("legacy", Uuid.random(), fileType = 100)),
                maxRecords = 100,
            )
        )

        val section = response.results.single()
        assertNull(section.status)
        assertTrue(section.isFailure)
        assertTrue(section.describeFailure().contains("status=unknown"))
    }

    @Test
    fun failedSectionDescribesItselfForLogging() = runTest {
        val mockEngine = MockEngine {
            respondJson(
                """
                {
                  "results": [
                    {
                      "name": "location",
                      "invalidDrive": true,
                      "status": "driveNotFound",
                      "errorCode": "invalidDrive",
                      "errorMessage": "Invalid drive id",
                      "hasMoreRows": false,
                      "searchResults": []
                    }
                  ]
                }
                """
            )
        }

        val provider = buildProvider(mockEngine)
        val response = provider.queryBatchCollection(
            QueryBatchCollectionRequest(
                queries = listOf(section("location", Uuid.random(), fileType = 100)),
                maxRecords = 100,
            )
        )

        val described = response.results.single().describeFailure()
        assertTrue(described.contains("section=location"), described)
        assertTrue(described.contains("status=DriveNotFound"), described)
        assertTrue(described.contains("code=invalidDrive"), described)
        assertTrue(described.contains("Invalid drive id"), described)
    }

    @Test
    fun budgetBelowOneIsRejectedBeforeTheRequestIsSent() = runTest {
        // The server 400s on this; failing locally keeps a whole sync cycle from burning a round trip.
        var called = false
        val mockEngine = MockEngine {
            called = true
            respondJson("""{ "results": [] }""")
        }

        val provider = buildProvider(mockEngine)
        assertFailsWith<IllegalArgumentException> {
            provider.queryBatchCollection(
                QueryBatchCollectionRequest(
                    queries = listOf(section("a", Uuid.random(), fileType = 100)),
                    maxRecords = 0,
                )
            )
        }
        assertFalse(called)
    }
}
