package id.homebase.api.client.drives.files

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.upload.CreateFileResult
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateFileResult
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.createInMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

// Feed comments live in the Comment file system, which sync never reads: the outbox is the only place the
// local copy can learn its server identity, and every write to it must name the Comment file system.
class DriveOutboxUploaderCommentFileTest {

    private val driveId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val uniqueId = Uuid.parse("dddddddd-dddd-dddd-dddd-dddddddddddd")
    private val optimisticFileId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val serverFileId = Uuid.parse("cccccccc-cccc-cccc-cccc-cccccccccccc")
    private val serverVersionTag = Uuid.parse("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
    private val serverGtid = Uuid.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")

    private val requests = mutableListOf<Pair<HttpMethod, Url>>()

    private val mockEngine = MockEngine { request ->
        requests.add(request.method to request.url)
        val body = when {
            request.method == HttpMethod.Patch -> OdinSystemSerializer.serialize(
                UpdateFileResult(fileId = serverFileId, driveId = driveId, newVersionTag = Uuid.random())
            )
            request.url.encodedPath.contains("delete-batch") ->
                OdinSystemSerializer.serialize(DeleteFileIdBatchResult(results = emptyList()))
            else -> OdinSystemSerializer.serialize(
                CreateFileResult(
                    fileId = serverFileId,
                    driveId = driveId,
                    globalTransitId = serverGtid,
                    newVersionTag = serverVersionTag,
                )
            )
        }
        respond(body, HttpStatusCode.OK)
    }

    private val httpClient = HttpClient(mockEngine)
    private val credentialsManager = CredentialsManager()
    private var tempDir: String = ""
    private lateinit var dbm: DatabaseManager

    private val fileOps = object : FileOperationsProvider {
        override fun getCacheDirectory() = tempDir
        override fun openFileInput(path: String): InputProvider = error("not used")
        override suspend fun readFileBytes(path: String): ByteArray = error("not used")
        override fun deleteTempFile(path: String) = false
        override fun getFileSize(path: String) = 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
    }

    @BeforeTest
    fun setup() = runBlocking {
        tempDir = Files.createTempDirectory("hb-uploader-comment-test").toString()
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("owner.test"),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(16)),
            )
        )
        dbm = DatabaseManager({ createInMemoryDatabase() })
    }

    @AfterTest
    fun tearDown() {
        dbm.close()
        httpClient.close()
        runCatching {
            Files.walk(java.nio.file.Path.of(tempDir))
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private fun uploader() = DriveOutboxUploader(
        driveUploadProvider = DriveUploadProvider(httpClient, credentialsManager, fileOps),
        fileProvider = DriveFileProvider(
            httpClient, credentialsManager, DriveFileProviderCached(httpClient, credentialsManager, fileOps),
        ),
        operationsProvider = DriveFileOperationsProvider(httpClient, credentialsManager),
        reactionProvider = DriveFileGroupReactionProvider(httpClient, credentialsManager),
        databaseManager = dbm,
        credentialsManager = credentialsManager,
    )

    private suspend fun identityId() = credentialsManager.requireActiveCredentials().getIdentityId()

    private suspend fun insertOptimisticRow() {
        val file = HomebaseFile(
            fileId = optimisticFileId,
            driveId = driveId,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader.newRandom16(),
            fileMetadata = FileMetadata(appData = AppFileMetaData(uniqueId = uniqueId, fileType = 801)),
            serverMetadata = ServerMetadata(),
        )
        val record = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
            .convertFileHeaderToDriveMainIndexRecord(identityId(), driveId, file)
        MainIndexMetaHelpers.upsertDriveMainIndex(dbm, record)
    }

    private fun outbox(uploadType: Long, request: Any): Outbox = Outbox(
        rowId = 1L,
        driveId = driveId,
        uniqueId = uniqueId,
        dependencyUniqueId = null,
        priority = 0L,
        lastAttempt = 0L,
        nextRunTime = 0L,
        checkOutCount = 0L,
        checkOutStamp = 1L,
        uploadType = uploadType,
        json = when (request) {
            is UploadFileRequest -> OdinSystemSerializer.serialize(request)
            is UpdateFileByUniqueIdRequest -> OdinSystemSerializer.serialize(request)
            is DeleteLocalFilesByFileIdRequest -> OdinSystemSerializer.serialize(request)
            else -> error("unsupported")
        }.encodeToByteArray(),
        files = null,
    )

    private fun create(fileSystemType: FileSystemType?) = outbox(
        DriveOutboxUploader.UploadNewFile,
        UploadFileRequest(
            driveId = driveId,
            keyHeader = KeyHeader.empty(),
            metadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = false,
                appData = UploadAppFileMetaData(uniqueId = uniqueId, fileType = 801, content = "{}"),
            ),
            fileSystemType = fileSystemType,
        ),
    )

    @Test
    fun `a Comment file create gives the local row the server's fileId, versionTag and globalTransitId`() = runTest {
        insertOptimisticRow()

        uploader().upload(create(FileSystemType.Comment), EventBus())

        val local = dbm.driveMainIndex.selectHomebaseFileByUnique(identityId(), driveId, uniqueId)
        assertEquals(serverFileId, local?.fileId)
        assertEquals(serverVersionTag, local?.fileMetadata?.versionTag)
        assertEquals(serverGtid, local?.fileMetadata?.globalTransitId)
    }

    @Test
    fun `a Standard file create leaves the local row for sync to replace`() = runTest {
        insertOptimisticRow()

        uploader().upload(create(fileSystemType = null), EventBus())

        val local = dbm.driveMainIndex.selectHomebaseFileByUnique(identityId(), driveId, uniqueId)
        assertEquals(optimisticFileId, local?.fileId)
        assertNull(local?.fileMetadata?.versionTag)
    }

    @Test
    fun `an update and a delete of a Comment file name the Comment file system`() = runTest {
        val update = UpdateFileByUniqueIdRequest(
            driveId = driveId,
            uniqueId = uniqueId,
            keyHeader = KeyHeader.empty(),
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArray(16),
                locale = UpdateLocale.Local,
                recipients = emptyList(),
                manifest = UpdateManifest.build(payloads = null, toDeletePayloads = null, thumbnails = null, generatePayloadIv = false),
            ),
            metadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = false,
                versionTag = serverVersionTag,
                appData = UploadAppFileMetaData(uniqueId = uniqueId, fileType = 801, content = "{}"),
            ),
            fileSystemType = FileSystemType.Comment,
        )
        val delete = DeleteLocalFilesByFileIdRequest(
            driveId = driveId,
            fileIds = listOf(serverFileId),
            fileSystemType = FileSystemType.Comment,
        )

        uploader().upload(outbox(DriveOutboxUploader.UpdateFile, update), EventBus())
        uploader().upload(outbox(DriveOutboxUploader.DeleteFile, delete), EventBus())

        assertEquals(listOf("Comment", "Comment"), requests.map { it.second.parameters["xfst"] })
    }
}
