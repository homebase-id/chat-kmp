package id.homebase.api.client.drives.query

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.CollectionQueryParamSection
import id.homebase.api.client.drives.QueryBatchCollectionRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * [DriveQueryProvider.queryBatchCollection] — POST /drives/query-batch-collection. Pins the
 * request shape (no driveId path segment; each section's driveId rides in the body instead)
 * and the per-section decode: a healthy section's rows go through the same salvage-decode as
 * [DriveQueryProvider.queryBatch], and an invalidDrive section comes back empty rather than
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

    @Test
    fun decodesEachNamedSectionIndependently() = runTest {
        val photosDriveId = Uuid.random()
        val docsDriveId = Uuid.random()
        val fileId = Uuid.random()

        lateinit var capturedRequest: HttpRequestData
        val mockEngine = MockEngine { request ->
            capturedRequest = request
            respond(
                """
                {
                  "results": [
                    {
                      "name": "photos",
                      "invalidDrive": false,
                      "queryTime": 0,
                      "includeMetadataHeader": false,
                      "cursorState": "next-page-cursor",
                      "hasMoreRows": true,
                      "searchResults": [${serverFileJson(fileId, photosDriveId)}]
                    },
                    {
                      "name": "docs",
                      "invalidDrive": true,
                      "queryTime": 0,
                      "includeMetadataHeader": false,
                      "cursorState": null,
                      "hasMoreRows": false,
                      "searchResults": []
                    }
                  ]
                }
                """.trimIndent(),
                HttpStatusCode.OK,
            )
        }

        val provider = buildProvider(mockEngine)
        val request = QueryBatchCollectionRequest(
            queries = listOf(
                CollectionQueryParamSection(
                    name = "photos",
                    driveId = photosDriveId,
                    queryParams = FileQueryParams(fileType = listOf(100)),
                    resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = 50),
                ),
                CollectionQueryParamSection(
                    name = "docs",
                    driveId = docsDriveId,
                    queryParams = FileQueryParams(fileType = listOf(200)),
                    resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = 25),
                ),
            )
        )

        val response = provider.queryBatchCollection(request)

        // Request shape: no driveId path segment — each section's driveId rides in the body.
        assertEquals("/api/v2/drives/query-batch-collection", capturedRequest.url.encodedPath)
        val sentBody = parseRequest(capturedRequest)
        assertEquals(listOf("photos", "docs"), sentBody.queries.map { it.name })
        assertEquals(photosDriveId, sentBody.queries[0].driveId)
        assertEquals(50, sentBody.queries[0].resultOptionsRequest.maxRecords)

        // Response: sections come back matched by name, each decoded independently.
        assertEquals(2, response.results.size)

        val photos = response.results.first { it.name == "photos" }
        assertFalse(photos.invalidDrive)
        assertEquals("next-page-cursor", photos.cursorState)
        assertTrue(photos.hasMoreRows)
        assertEquals(1, photos.searchResults.size)
        assertEquals(fileId, photos.searchResults[0].fileId)

        val docs = response.results.first { it.name == "docs" }
        assertTrue(docs.invalidDrive)
        assertTrue(docs.searchResults.isEmpty())
        assertFalse(docs.hasMoreRows)
    }
}
