package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.filesync.FilesyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FilesyncRecoveredFilesTest {

    private val secret = ByteArray(16)
    private val asked = mutableListOf<QueryBatchRequest>()

    private val row = """
        {
          "fileId": "${Uuid.random()}",
          "driveId": "${FilesyncProtocol.DriveAlias}",
          "fileState": "active",
          "fileSystemType": "standard",
          "sharedSecretEncryptedKeyHeader": { "encryptionVersion": 1, "iv": "AAAA", "encryptedAesKey": "AAAA" },
          "fileMetadata": { "versionTag": "${Uuid.random()}", "appData": { "uniqueId": "${Uuid.random()}", "fileType": 7102 } },
          "serverMetadata": {}
        }
    """

    private suspend fun repository(rows: List<String>): FilesyncRepository {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("frodo.dotyou.cloud"), "token", SecureByteArray(secret.copyOf())))
        val engine = MockEngine { request ->
            asked += OdinSystemSerializer.deserialize<QueryBatchRequest>(CryptoHelper.decryptContentAsString(request.body.toByteArray().decodeToString(), secret))
            respond("""{"searchResults":[${rows.joinToString(",")}],"hasMoreRows":false}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return testFilesyncRepository(HttpClient(engine), credentials)
    }

    @Test
    fun itAsksForOneRowOfWhatTheFolderWouldList() = runTest {
        assertTrue(repository(listOf(row)).holdsAny(FilesyncProtocol.RecoveredFilesNodeId, includeRemoved = false))
        assertFalse(repository(emptyList()).holdsAny(FilesyncProtocol.RecoveredFilesNodeId, includeRemoved = true))

        val (live, withRemoved) = asked
        assertEquals(listOf(FilesyncProtocol.RecoveredFilesNodeId), live.queryParams.groupId)
        assertEquals(listOf(FilesyncProtocol.FolderFileType, FilesyncProtocol.FileFileType), live.queryParams.fileType)
        assertEquals(listOf(0, 1, 3), live.queryParams.archivalStatus)
        assertEquals(listOf(0, 1, 3, 2), withRemoved.queryParams.archivalStatus)
        assertEquals(1, live.resultOptionsRequest.maxRecords)
    }
}
