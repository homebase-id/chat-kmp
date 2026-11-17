package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.FileVersionTagBatch
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadHash
import id.homebase.api.client.drives.files.PerFileBatchResult
import id.homebase.api.client.drives.files.PerFileResult
import id.homebase.api.client.drives.upload.CreateFileResult
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.UpdateFileResult
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.filesync.FilesyncRoleFolder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import okio.FileSystem
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Restores against a drive held in memory, for the loops two writers' moves leave (filesync's restore.rs, loop_exit). */
class FilesyncRestoreTest {

    private class Row(val nodeId: Uuid, var parent: Uuid, val folder: Boolean, var name: String, var archival: Int, var updated: Long) {
        val fileId: Uuid = Uuid.random()
        var versionTag: Uuid = Uuid.random()
        val key: KeyHeader = KeyHeader.newRandom16()
        var created = false
    }

    private val secret = ByteArray(16) { 5 }
    private val rows = linkedMapOf<Uuid, Row>()
    private var clock = 1_000L
    // A node with Recovered files' id that this device can't read.
    private var recoveredIdTaken = false
    private lateinit var editor: FilesyncEditor

    @BeforeTest
    fun setup() = runBlocking {
        val credentials = CredentialsManager()
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("pippin.dotyou.cloud"), "token", SecureByteArray(secret.copyOf())))
        val http = HttpClient(MockEngine { request -> serve(request) })
        val fileOps = OkioFileOperationsProvider(FileSystem.SYSTEM, Files.createTempDirectory("filesync-restore").toString())
        editor = FilesyncEditor(
            testFilesyncRepository(http, credentials),
            DriveUploadProvider(http, credentials, fileOps),
            DriveFileHttpProvider(http, credentials),
            FilesyncUploader(DriveUploadProvider(http, credentials, fileOps), fileOps),
        )
    }

    private fun add(name: String, parent: Uuid, folder: Boolean = true, removedAt: Long? = null, id: Uuid = Uuid.random()) =
        Row(id, parent, folder, name, if (removedAt == null) 0 else 2, removedAt ?: 1).also { rows[id] = it }

    private fun live(row: Row) = row.archival != 2

    private suspend fun MockRequestHandleScope.serve(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val body = request.body.toByteArray()
        return when {
            path.endsWith("/query-batch") -> {
                val params = OdinSystemSerializer.deserialize<QueryBatchRequest>(CryptoHelper.decryptContentAsString(body.decodeToString(), secret)).queryParams
                val found = rows.values.filter { row ->
                    params.fileState.orEmpty().all { it == FileState.Active } &&
                        (params.archivalStatus == null || row.archival in params.archivalStatus!!) &&
                        (params.groupId == null || row.parent in params.groupId!!) &&
                        (params.clientUniqueIdAtLeastOne == null || row.nodeId in params.clientUniqueIdAtLeastOne!!)
                }
                val files = found.map { serverFile(it) }
                json("""{"searchResults":[${files.joinToString(",")}],"hasMoreRows":false,"queryTime":${clock}}""")
            }
            path.endsWith("/update-archival-status-batch") -> {
                val batch = OdinSystemSerializer.deserialize<FileVersionTagBatch>(CryptoHelper.decryptContentAsString(body.decodeToString(), secret))
                val results = batch.files.map { file ->
                    val row = rows.values.single { it.fileId == file.fileId }
                    if (row.versionTag != file.versionTag) return@map PerFileResult(file.fileId, OdinClientErrorCode.VersionTagMismatch)
                    row.archival = batch.archivalStatus!!.value
                    row.touch()
                    PerFileResult(file.fileId)
                }
                json(OdinSystemSerializer.serialize(PerFileBatchResult(results)))
            }
            path.endsWith("/files") -> {
                val (appData, name) = sent(body, request)
                val nodeId = Uuid.parse(appData.getValue("uniqueId").jsonPrimitive.content)
                if (recoveredIdTaken && nodeId == FilesyncProtocol.RecoveredFilesNodeId || nodeId in rows) {
                    return respond("""{"title":"taken","status":400,"errorCode":4105}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/problem+json"))
                }
                val row = add(name, Uuid.parse(appData.getValue("groupId").jsonPrimitive.content), id = nodeId).apply { created = true; touch() }
                json(OdinSystemSerializer.serialize(CreateFileResult(row.fileId, FilesyncProtocol.DriveAlias, newVersionTag = row.versionTag)))
            }
            else -> {
                val row = rows.values.single { path.endsWith("/files/${it.fileId}") }
                val (appData, name) = sent(body, request, row.key)
                row.parent = Uuid.parse(appData.getValue("groupId").jsonPrimitive.content)
                row.archival = appData.getValue("archivalStatus").jsonPrimitive.int
                row.name = name
                row.touch()
                json(OdinSystemSerializer.serialize(UpdateFileResult(row.fileId, FilesyncProtocol.DriveAlias, newVersionTag = row.versionTag)))
            }
        }
    }

    private fun Row.touch() {
        versionTag = Uuid.random()
        updated = ++clock
    }

    // The appData a write sent, and the name in its content, opened with the key it was sent under.
    private suspend fun sent(body: ByteArray, request: HttpRequestData, expectedKey: KeyHeader? = null): Pair<Map<String, kotlinx.serialization.json.JsonElement>, String> {
        val descriptor = uploadDescriptor(body, request, secret)
        val key = OdinSystemSerializer.json.decodeFromJsonElement(EncryptedKeyHeader.serializer(), descriptor["encryptedKeyHeader"]!!)
            .decryptAesToKeyHeader(SecureByteArray(secret))
        if (expectedKey != null) assertTrue(key.aesKey.toByteArray().contentEquals(expectedKey.aesKey.toByteArray()), "a restore keeps the node's key")
        val appData = descriptor["fileMetadata"]!!.jsonObject["appData"]!!.jsonObject
        val content = Json.parseToJsonElement(key.decrypt(Base64.decode(appData["content"]!!.jsonPrimitive.content)).decodeToString()).jsonObject
        return appData to content["name"]!!.jsonPrimitive.content
    }

    private suspend fun serverFile(row: Row): String = OdinSystemSerializer.serialize(
        ServerFile(
            fileId = row.fileId,
            driveId = FilesyncProtocol.DriveAlias,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            sharedSecretEncryptedKeyHeader = EncryptedKeyHeader.encryptKeyHeaderAes(row.key, ByteArray(16) { 9 }, SecureByteArray(secret)),
            fileMetadata = FileMetadata(
                updated = UnixTimeUtc(row.updated),
                isEncrypted = true,
                versionTag = row.versionTag,
                payloads = if (row.folder) null else listOf(
                    PayloadDescriptor(FilesyncProtocol.PayloadKey, iv = Base64Bytes(ByteArray(16)), bytesWritten = 16, hash = PayloadHash("blake3")),
                ),
                appData = AppFileMetaData(
                    uniqueId = row.nodeId,
                    groupId = row.parent,
                    fileType = if (row.folder) FilesyncProtocol.FolderFileType else FilesyncProtocol.FileFileType,
                    archivalStatus = ArchivalStatus.fromInt(row.archival),
                    content = Base64.encode(row.key.encryptDataAes("""{"v":1,"name":"${row.name}"${if (row.folder) "" else ""","size":1"""}}""".encodeToByteArray())),
                ),
            ),
            serverMetadata = ServerMetadata(),
        )
    )

    private fun MockRequestHandleScope.json(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    // Two folders each in the other: A moved into B on one computer while B was moved into A on another, then both deleted.
    private fun loop(aName: String = "A", bName: String = "B", aId: Uuid = Uuid.random()): Pair<Row, Row> {
        val bId = Uuid.random()
        return add(aName, bId, removedAt = 100, id = aId) to add(bName, aId, removedAt = 100, id = bId)
    }

    private fun Row.isLiveIn(folder: Uuid) = live(this) && parent == folder

    @Test
    fun theItemOfALoopComesBackIntoRecoveredFilesMadeFirstAndTheRestIntoIt() = runTest {
        val (a, b) = loop()
        val file = add("plan.txt", b.nodeId, folder = false, removedAt = 100)

        val result = editor.restore(setOf(a.nodeId))

        val recovered = rows.getValue(FilesyncProtocol.RecoveredFilesNodeId)
        assertTrue(recovered.created && recovered.isLiveIn(FilesyncProtocol.RootNodeId) && recovered.name == "Recovered files")
        assertTrue(a.isLiveIn(FilesyncProtocol.RecoveredFilesNodeId), "the item breaks the loop")
        assertTrue(b.isLiveIn(a.nodeId) && file.isLiveIn(b.nodeId), "the rest of the loop follows into it")
        assertEquals(3, result.done)
        assertTrue(result.loopsIntoRecovered)
    }

    @Test
    fun anItemBelowALoopBreaksItAtTheLoopsFolderItIsIn() = runTest {
        val (a, b) = loop()
        val inner = add("inner", a.nodeId, removedAt = 50)

        editor.restore(setOf(inner.nodeId))

        assertTrue(a.isLiveIn(FilesyncProtocol.RecoveredFilesNodeId), "the walk up from inner meets the loop at A")
        assertTrue(b.isLiveIn(a.nodeId) && inner.isLiveIn(a.nodeId))
    }

    @Test
    fun aLiveRecoveredFilesIsUsedAndATakenNameThereGetsRestored() = runTest {
        add("Recovered files", FilesyncProtocol.RootNodeId, id = FilesyncProtocol.RecoveredFilesNodeId)
        add("A", FilesyncProtocol.RecoveredFilesNodeId)
        val (a, b) = loop()

        editor.restore(setOf(a.nodeId))

        assertFalse(rows.values.any { it.created }, "nothing is made")
        assertTrue(a.isLiveIn(FilesyncProtocol.RecoveredFilesNodeId) && a.name == "A (restored)")
        assertTrue(b.isLiveIn(a.nodeId) && b.name == "B")
    }

    @Test
    fun aRecoveredFilesInRecentlyDeletedIsMovedIntoNotRestoredOrMade() = runTest {
        val recovered = add("Recovered files", FilesyncProtocol.RootNodeId, removedAt = 50, id = FilesyncProtocol.RecoveredFilesNodeId)
        val (a, b) = loop()

        val result = editor.restore(setOf(a.nodeId))

        assertFalse(live(recovered), "the computers bring it back")
        assertTrue(a.isLiveIn(FilesyncProtocol.RecoveredFilesNodeId) && b.isLiveIn(a.nodeId))
        assertEquals(1, result.recovered, "shown nowhere until a computer brings Recovered files back")
        assertFalse(result.loopsIntoRecovered)
    }

    @Test
    fun aFolderInItselfComesBackIntoRecoveredFiles() = runTest {
        val id = Uuid.random()
        val self = add("Self", id, removedAt = 100, id = id)

        val result = editor.restore(setOf(id))

        assertTrue(self.isLiveIn(FilesyncProtocol.RecoveredFilesNodeId))
        assertTrue(result.loopsIntoRecovered)
    }

    @Test
    fun aLoopThroughALiveFolderComesBackIntoIt() = runTest {
        // A in B, B in the live L that went with A's delete, L in A: filesync's walk stops at L, so nothing moves.
        val aId = Uuid.random()
        val bId = Uuid.random()
        val l = add("L", aId, id = Uuid.random()).apply { updated = 50 }
        val a = add("A", bId, removedAt = 100, id = aId)
        val b = add("B", l.nodeId, removedAt = 100, id = bId)

        editor.restore(setOf(a.nodeId))

        assertTrue(b.isLiveIn(l.nodeId) && a.isLiveIn(bId) && l.isLiveIn(aId))
        assertFalse(FilesyncProtocol.RecoveredFilesNodeId in rows)
    }

    @Test
    fun aRenamedRestoreIntoRecoveredFilesWithoutALoopSaysRestored() = runTest {
        add("Recovered files", FilesyncProtocol.RootNodeId, id = FilesyncProtocol.RecoveredFilesNodeId)
        add("x.txt", FilesyncProtocol.RecoveredFilesNodeId, folder = false)
        val x = add("x.txt", FilesyncProtocol.RecoveredFilesNodeId, folder = false, removedAt = 100)

        val result = editor.restore(setOf(x.nodeId))

        assertTrue(x.isLiveIn(FilesyncProtocol.RecoveredFilesNodeId) && x.name == "x (restored).txt")
        assertFalse(result.loopsIntoRecovered)
    }

    @Test
    fun aLoopRestoredWithSomethingSkippedStillSaysSo() = runTest {
        val sent = mutableListOf<FilesyncUiEvent>()
        reportWrite(FilesyncUiEvent.Notice(FilesyncNotice.Restored), FilesyncError.RestoreFailed, { sent += it }) {
            FilesyncWriteResult(done = 2, skipped = 1, loopsIntoRecovered = true)
        }
        assertEquals<List<FilesyncUiEvent>>(
            listOf(FilesyncUiEvent.Notice(FilesyncNotice.RestoredIntoRecovered), FilesyncUiEvent.Notice(FilesyncNotice.ChangedElsewhere)),
            sent,
        )
    }

    @Test
    fun whereRecoveredFilesIdIsTakenTheLoopComesBackAtTheTop() = runTest {
        recoveredIdTaken = true
        add("A", FilesyncProtocol.RootNodeId)
        val (a, b) = loop()

        editor.restore(setOf(a.nodeId))

        assertTrue(a.isLiveIn(FilesyncProtocol.RootNodeId) && a.name == "A (restored)")
        assertTrue(b.isLiveIn(a.nodeId))
    }

    @Test
    fun whereRecoveredFilesIsInALoopItselfTheLoopComesBackAtTheTop() = runTest {
        val other = Uuid.random()
        add("Recovered files", other, id = FilesyncProtocol.RecoveredFilesNodeId)
        add("elsewhere", FilesyncProtocol.RecoveredFilesNodeId, id = other)
        val (a, b) = loop()

        editor.restore(setOf(a.nodeId))

        assertTrue(a.isLiveIn(FilesyncProtocol.RootNodeId) && a.name == "A")
        assertTrue(b.isLiveIn(a.nodeId))
    }

    @Test
    fun aLoopsFixedFolderComesBackAtTheTopAndTheRestFollowsIt() = runTest {
        val documents = FilesyncRoleFolder.Documents.nodeId
        val (docs, b) = loop(aName = "Documents", aId = documents)

        editor.restore(setOf(b.nodeId))

        assertTrue(docs.isLiveIn(FilesyncProtocol.RootNodeId))
        assertTrue(b.isLiveIn(documents))
        assertFalse(FilesyncProtocol.RecoveredFilesNodeId in rows, "a fixed folder breaks it: nothing goes to Recovered files")
    }

    @Test
    fun aRestoreWithoutALoopIsAsBefore() = runTest {
        val photos = add("Photos", FilesyncProtocol.RootNodeId, removedAt = 100)
        val a = add("a.jpg", photos.nodeId, folder = false, removedAt = 100)

        val result = editor.restore(setOf(photos.nodeId))

        assertTrue(photos.isLiveIn(FilesyncProtocol.RootNodeId) && a.isLiveIn(photos.nodeId))
        assertFalse(result.loopsIntoRecovered)
        assertFalse(FilesyncProtocol.RecoveredFilesNodeId in rows)
    }
}
