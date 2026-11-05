package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class FilesyncRepositoryTest {

    private class Row(val folder: Uuid?, val archivalStatus: Int, val fileId: Uuid = Uuid.random(), val versionTag: Uuid = Uuid.random())

    private fun rowJson(row: Row) = """
        {
          "fileId": "${row.fileId}",
          "driveId": "${Uuid.random()}",
          "fileState": "active",
          "fileSystemType": "standard",
          "sharedSecretEncryptedKeyHeader": { "encryptionVersion": 1, "iv": "AAAA", "encryptedAesKey": "AAAA" },
          "fileMetadata": {
            "versionTag": "${row.versionTag}",
            "appData": { "uniqueId": "${Uuid.random()}", "fileType": 9999, "archivalStatus": ${row.archivalStatus}${row.folder?.let { ", \"groupId\": \"$it\"" } ?: ""} }
          },
          "serverMetadata": {}
        }
    """

    private suspend fun repository(rows: List<Row>): FilesyncRepository {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("frodo.dotyou.cloud"), "token", SecureByteArray(ByteArray(16))))
        val engine = MockEngine {
            respond(
                """{"searchResults":[${rows.joinToString(",", transform = ::rowJson)}],"hasMoreRows":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return testFilesyncRepository(HttpClient(engine), credentials)
    }

    @Test
    fun anyActiveFileKeepsItsFolderButTheRowsStillToGoAtTheirVersions() = runTest {
        val (live, removed, stillToGo, changedSince, empty) = List(5) { Uuid.random() }
        val toGo = Row(stillToGo, archivalStatus = 2)
        val written = Row(changedSince, archivalStatus = 2)
        val repository = repository(listOf(Row(live, 0), Row(removed, 2), toGo, written))

        val except = setOf(FileVersionTag(toGo.fileId, toGo.versionTag), FileVersionTag(written.fileId, Uuid.random()))
        assertEquals(setOf(live, removed, changedSince), repository.holding(listOf(live, removed, stillToGo, changedSince, empty), except))
    }

    @Test
    fun aRowWithoutAFolderKeepsEveryFolderAsked() = runTest {
        val folders = List(2) { Uuid.random() }
        assertEquals(folders.toSet(), repository(listOf(Row(null, 2))).holding(folders, emptySet()))
    }

    @Test
    fun aCachedFileKeepsItsNameInAFolderOfItsOwn() = runTest {
        val repository = repository(emptyList())
        val path = repository.writeToCache("Report: Q3?.pdf", toShareOutbound = false) { out -> out("hello".encodeToByteArray()) }
        assertEquals("Report_ Q3_.pdf", path.substringAfterLast('/'))
        assertEquals("hello", java.io.File(path).readText())

        val long = repository.writeToCache("x".repeat(400) + ".tar.gz", toShareOutbound = true) { out -> out(ByteArray(1)) }
        val name = long.substringAfterLast('/')
        assertTrue(name.encodeToByteArray().size <= 255 && name.endsWith(".gz"))

        repository.discard(path)
        assertFalse(java.io.File(path).exists())
    }
}
