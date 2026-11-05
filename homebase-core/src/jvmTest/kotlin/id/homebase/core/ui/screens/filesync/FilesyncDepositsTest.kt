package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.filesync.FilesyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

// Someone given Edit may add a file anywhere on the drive (the editors circle grants Write); theirs shows, saying who put it there.
class FilesyncDepositsTest {
    private val frodo = OdinId("frodo.test")
    private val secret = ByteArray(16) { 4 }

    private suspend fun node(name: String, sender: OdinId?): String {
        val key = KeyHeader.newRandom16()
        return OdinSystemSerializer.serialize(
            ServerFile(
                fileId = Uuid.random(),
                driveId = FilesyncProtocol.DriveAlias,
                fileState = FileState.Active,
                fileSystemType = FileSystemType.Standard,
                sharedSecretEncryptedKeyHeader = EncryptedKeyHeader.encryptKeyHeaderAes(key, ByteArray(16) { 9 }, SecureByteArray(secret)),
                fileMetadata = FileMetadata(
                    isEncrypted = true,
                    senderOdinId = sender,
                    appData = AppFileMetaData(
                        uniqueId = Uuid.random(),
                        groupId = FilesyncProtocol.RootNodeId,
                        fileType = FilesyncProtocol.FolderFileType,
                        content = Base64.encode(key.encryptDataAes("""{"v":1,"name":"$name"}""".encodeToByteArray())),
                    ),
                ),
                serverMetadata = ServerMetadata(),
            )
        )
    }

    @Test
    fun aNodeLeftBySomeoneElseSaysWhoAddedIt() = runTest {
        val page = listOf(node("Mine", frodo), node("Older", sender = null), node("Planted", OdinId("pippin.test")))
        val engine = MockEngine {
            respond("""{"searchResults":[${page.joinToString(",")}],"hasMoreRows":false}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(frodo, "token", SecureByteArray(secret)))

        val listed = testFilesyncRepository(HttpClient(engine), credentials).listFolder(FilesyncProtocol.RootNodeId)

        assertEquals(mapOf("Mine" to null, "Older" to null, "Planted" to OdinId("pippin.test")), listed.nodes.values.associate { it.name to it.addedBy })
    }
}
