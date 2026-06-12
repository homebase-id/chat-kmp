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
import id.homebase.api.client.drives.upload.PayloadUploadReceipt
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
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * [DriveOutboxUploader.uploadNewFile]'s cache re-key: after a successful
 * create, seeded cache entries must move from the local optimistic fileId to
 * the server-assigned one, with thumb target keys built from the response's
 * per-payload receipts — and a re-key failure must NEVER surface as an upload
 * failure (OutboxSync would re-send the file to recipients).
 */
class DriveOutboxUploaderRekeyTest {

    private val driveId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val uniqueId = Uuid.parse("dddddddd-dddd-dddd-dddd-dddddddddddd")
    private val optimisticFileId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val serverFileId = Uuid.parse("cccccccc-cccc-cccc-cccc-cccccccccccc")
    private val payloadKey = "photo"
    private val serverLastModified = 1_780_000_000_000L

    private var uploadResponse: CreateFileResult? = null
    private var cacheReadRequests = 0

    private val mockEngine = MockEngine { request ->
        if (request.method == HttpMethod.Post && request.url.encodedPath.contains("/files")) {
            respond(OdinSystemSerializer.serialize(uploadResponse!!), HttpStatusCode.OK)
        } else {
            // Any other request is a cache miss falling through to the network —
            // the rekey assertions count these.
            cacheReadRequests++
            respondError(HttpStatusCode.NotFound)
        }
    }

    private val httpClient = HttpClient(mockEngine)
    private val credentialsManager = CredentialsManager()
    private var tempDir: String = ""
    private lateinit var dbm: DatabaseManager
    private lateinit var driveCache: DriveFileProviderCached
    private lateinit var fileProvider: DriveFileProvider

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
        tempDir = Files.createTempDirectory("hb-uploader-rekey-test").toString()
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("owner.test"),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(16)),
            )
        )
        dbm = DatabaseManager({ createInMemoryDatabase() })
        driveCache = DriveFileProviderCached(httpClient, credentialsManager, fileOps)
        fileProvider = DriveFileProvider(httpClient, credentialsManager, driveCache)
        cacheReadRequests = 0
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

    private fun uploader(databaseManager: DatabaseManager = dbm) = DriveOutboxUploader(
        driveUploadProvider = DriveUploadProvider(httpClient, credentialsManager, fileOps),
        fileProvider = fileProvider,
        operationsProvider = DriveFileOperationsProvider(httpClient, credentialsManager),
        reactionProvider = DriveFileGroupReactionProvider(httpClient, credentialsManager),
        databaseManager = databaseManager,
        credentialsManager = credentialsManager,
    )

    private suspend fun insertOptimisticRow() {
        val file = HomebaseFile(
            fileId = optimisticFileId,
            driveId = driveId,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader.newRandom16(),
            fileMetadata = id.homebase.api.client.drives.files.FileMetadata(
                appData = AppFileMetaData(uniqueId = uniqueId, fileType = 7878),
                payloads = listOf(
                    PayloadDescriptor(
                        key = payloadKey,
                        contentType = "image/jpeg",
                        thumbnails = listOf(
                            ThumbnailDescriptor(pixelWidth = 100, pixelHeight = 100, contentType = "image/webp"),
                        ),
                    ),
                ),
            ),
            serverMetadata = ServerMetadata(),
        )
        val identityId = credentialsManager.requireActiveCredentials().getIdentityId()
        val record = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
            .convertFileHeaderToDriveMainIndexRecord(identityId, driveId, file)
        MainIndexMetaHelpers.upsertDriveMainIndex(dbm, record)
    }

    private fun outboxRecord(): Outbox {
        val request = UploadFileRequest(
            driveId = driveId,
            keyHeader = KeyHeader.newRandom16(),
            metadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(uniqueId = uniqueId, fileType = 7878, content = "{}"),
            ),
        )
        return Outbox(
            rowId = 1L,
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0L,
            lastAttempt = 0L,
            nextRunTime = 0L,
            checkOutCount = 0L,
            checkOutStamp = 1L,
            uploadType = DriveOutboxUploader.UploadNewFile,
            json = OdinSystemSerializer.serialize(request).encodeToByteArray(),
            files = null,
        )
    }

    private fun resultWithReceipts() = CreateFileResult(
        fileId = serverFileId,
        driveId = driveId,
        newVersionTag = Uuid.random(),
        payloads = listOf(PayloadUploadReceipt(key = payloadKey, uid = 1L, lastModified = serverLastModified)),
    )

    @Test
    fun `successful create moves seeded entries to the server fileId using the receipts`() = runTest {
        val payloadBytes = ByteArray(64) { it.toByte() }
        val thumbBytes = ByteArray(32) { (it * 3).toByte() }
        driveCache.cachePayloadBytesEncrypted(driveId, optimisticFileId, payloadKey, payloadBytes, "image/jpeg")
        driveCache.cacheThumbBytesEncrypted(driveId, optimisticFileId, payloadKey, 100, 100, thumbBytes, "image/webp")
        insertOptimisticRow()
        uploadResponse = resultWithReceipts()

        uploader().upload(outboxRecord(), EventBus())

        val payload = driveCache.getPayloadBytesRaw(driveId, serverFileId, payloadKey)
        assertContentEquals(payloadBytes, payload.bytes)
        // The thumb must be readable under the RECEIPT's lastModified — the key
        // post-sync readers build (the seed was written under null).
        val thumb = driveCache.getThumbBytesRaw(driveId, serverFileId, payloadKey, 100, 100, serverLastModified)
        assertContentEquals(thumbBytes, thumb.bytes)
        assertEquals(0, cacheReadRequests, "moved entries must be served without any network reads")
    }

    @Test
    fun `receipts-less response still moves the payload but not the thumb`() = runTest {
        // Old server: no receipts → the thumb's target key (server lastModified)
        // can't be computed, so only the payload entry moves.
        driveCache.cachePayloadBytesEncrypted(driveId, optimisticFileId, payloadKey, ByteArray(16) { 1 }, "image/jpeg")
        driveCache.cacheThumbBytesEncrypted(driveId, optimisticFileId, payloadKey, 100, 100, ByteArray(8), "image/webp")
        insertOptimisticRow()
        uploadResponse = resultWithReceipts().copy(payloads = emptyList())

        uploader().upload(outboxRecord(), EventBus())

        val payload = driveCache.getPayloadBytesRaw(driveId, serverFileId, payloadKey)
        assertEquals(200, payload.status)
        assertEquals(0, cacheReadRequests, "payload move must still work without receipts")
    }

    @Test
    fun `upload without a local optimistic row succeeds and rekeys nothing`() = runTest {
        // Vault-style upload with no optimistic record — must be a clean no-op.
        uploadResponse = resultWithReceipts()

        uploader().upload(outboxRecord(), EventBus())
        // No assertion beyond "did not throw": a throw here would re-send the file.
    }

    @Test
    fun `a rekey failure never surfaces as an upload failure`() = runTest {
        // A closed DatabaseManager makes the local-row lookup throw inside
        // rekeyCacheAfterCreate. upload() must still complete normally —
        // OutboxSync treats any throw as upload failure and would RE-SEND the
        // file to recipients.
        val closedDbm = DatabaseManager({ createInMemoryDatabase() }).also { it.close() }
        uploadResponse = resultWithReceipts()

        uploader(databaseManager = closedDbm).upload(outboxRecord(), EventBus())
        // Reaching here without an exception IS the assertion.
    }
}
