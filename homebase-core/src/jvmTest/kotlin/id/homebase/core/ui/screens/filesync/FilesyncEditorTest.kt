package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDownloadService
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.filesync.FilesyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** A restore writes the node back exactly as filesync's wire.rs does; a node changed elsewhere is skipped, not an error. */
class FilesyncEditorTest {

    private val nodeId = Uuid.random()
    private val fileKey = KeyHeader(ByteArray(16) { 7 }, SecureByteArray(ByteArray(16) { it.toByte() }))
    private val versionTag = Uuid.random()
    private val removedContent = OdinSystemSerializer.json.parseToJsonElement(
        """{"v":1,"name":"a.txt","mtime":1,"size":5,"ct":1451606400123,"tags":[{"n":"Red","c":6},{"n":"Work"}],"later":{"k":1},"rm":{"at":5,"root":"$nodeId"}}"""
    ).jsonObject
    private val handle = FilesyncHandle(Uuid.random(), versionTag, FilesyncProtocol.FileFileType, fileKey, removedContent, payload = null)

    private var reply: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"newVersionTag":"${Uuid.random()}"}""" }
    private var headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Odin-Version" to listOf("1.0"))
    private lateinit var editor: FilesyncEditor

    @BeforeTest
    fun setup() = runBlocking {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("frodo.dotyou.cloud"), "token", SecureByteArray(ByteArray(16) { 3 })))
        val engine = MockEngine {
            val (status, body) = reply()
            respond(body, status, headers)
        }
        val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, Files.createTempDirectory("filesync-editor").toString())
        val http = HttpClient(engine)
        val files = DriveFileProvider(http, credentials, DriveFileProviderCached(http, credentials, fileOps))
        editor = FilesyncEditor(
            FilesyncRepository(DriveQueryProvider(http, credentials), PayloadDownloadService(files, fileOps), fileOps, files),
            DriveUploadProvider(http, credentials, fileOps),
            DriveFileHttpProvider(http, credentials),
        )
    }

    @Test
    fun aRestoreIsALiveHeaderWithEveryKeyButRmUnderTheSameKey() = runTest {
        val target = Uuid.random()
        val request = liveNodeUpdate(nodeId, handle, restoredContent(removedContent, "a (restored).txt"), target)

        val appData = request.metadata.appData
        assertEquals(ArchivalStatus.None, appData.archivalStatus)
        assertEquals(target, appData.groupId)
        assertEquals(nodeId, appData.uniqueId)
        assertEquals(versionTag, request.metadata.versionTag)
        assertEquals(handle.fileId, request.fileId)
        assertEquals(UpdateLocale.Local, request.instructions.locale)
        assertNull(request.payloads)
        assertContentEquals(fileKey.aesKey.unsafeBytes, request.keyHeader!!.aesKey.unsafeBytes)
        assertFalse(fileKey.iv.contentEquals(request.keyHeader!!.iv))

        val content = OdinSystemSerializer.json.parseToJsonElement(request.keyHeader!!.decrypt(Base64.decode(appData.content!!)).decodeToString()) as JsonObject
        assertEquals(setOf("v", "name", "mtime", "size", "ct", "tags", "later"), content.keys)
        assertEquals(removedContent["tags"], content["tags"])
        assertEquals("\"a (restored).txt\"", content["name"].toString())
    }

    @Test
    fun aNodeChangedOrGoneElsewhereIsSkipped() = runTest {
        for (error in listOf(400 to 4160, 400 to 4106, 404 to 0)) {
            reply = { HttpStatusCode.fromValue(error.first) to """{"title":"no","status":${error.first},"errorCode":${error.second}}""" }
            assertFalse(editor.write(nodeId, handle, removedContent, FilesyncProtocol.RootNodeId), "$error")
        }
    }

    @Test
    fun aNotFoundFromInFrontOfTheIdentityIsAnError() = runTest {
        headers = headersOf(HttpHeaders.ContentType, "text/html")
        reply = { HttpStatusCode.NotFound to "<html>maintenance</html>" }
        assertFailsWith<NotFoundException> { editor.write(nodeId, handle, removedContent, FilesyncProtocol.RootNodeId) }
    }

    @Test
    fun anyOtherRefusalIsAnError() = runTest {
        reply = { HttpStatusCode.BadRequest to """{"title":"no","status":400,"errorCode":4001}""" }
        assertFailsWith<ClientException> { editor.write(nodeId, handle, removedContent, FilesyncProtocol.RootNodeId) }
    }
}
