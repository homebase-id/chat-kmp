package id.homebase.api.client.drives.files

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.createInMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** A text message whose create landed but whose 200 was lost must finish as sent, not be re-sent as an update the server rejects. */
class HeaderOnlyLostReplyRecoveryTest {

    private val driveId = Uuid.random()
    private val uniqueId = Uuid.random()
    private val secret = ByteArray(16)
    private val credentialsManager = CredentialsManager()
    private val dbm = DatabaseManager({ createInMemoryDatabase() })
    private val clientKey = KeyHeader.newRandom16()
    private var updatesSent = 0

    @AfterTest
    fun tearDown() = dbm.close()

    private val fileOps = object : FileOperationsProvider {
        override fun getCacheDirectory() = ""
        override fun openFileInput(path: String): InputProvider = error("not used")
        override suspend fun readFileBytes(path: String): ByteArray = error("not used")
        override fun deleteTempFile(path: String) = false
        override fun getFileSize(path: String) = 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
    }

    private suspend fun metadata(plaintext: String) = UploadFileMetadata(
        allowDistribution = false,
        isEncrypted = true,
        appData = UploadAppFileMetaData(uniqueId = uniqueId, fileType = 7878, content = plaintext),
    ).encryptContent(clientKey)

    private suspend fun serverHeaderBody(serverKey: KeyHeader, content: String?): String {
        val file = ServerFile(
            fileId = Uuid.random(),
            driveId = driveId,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            sharedSecretEncryptedKeyHeader = EncryptedKeyHeader.encryptKeyHeaderAes(serverKey, ByteArrayUtil.getRndByteArray(16), SecureByteArray(secret.copyOf())),
            fileMetadata = FileMetadata(isEncrypted = true, appData = AppFileMetaData(uniqueId = uniqueId, fileType = 7878, content = content)),
            serverMetadata = ServerMetadata(),
        )
        return OdinSystemSerializer.serialize(CryptoHelper.encryptData(OdinSystemSerializer.serialize(file), secret))
    }

    private fun uploader(serverHeaderBody: String): DriveOutboxUploader {
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Get && path.contains("/by-uid/") -> respond(serverHeaderBody, HttpStatusCode.OK)
                request.method == HttpMethod.Post && path.endsWith("/files") -> respond(
                    """{"status":400,"title":"File already exists with ClientUniqueId","extensions":{"errorCode":"ExistingFileWithUniqueId"}}""",
                    HttpStatusCode.BadRequest,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> {
                    updatesSent++
                    respond(
                        """{"status":400,"title":"When updating a file, you must change the Iv","extensions":{"errorCode":"UnhandledScenario"}}""",
                        HttpStatusCode.BadRequest,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        })
        return DriveOutboxUploader(
            driveUploadProvider = DriveUploadProvider(http, credentialsManager, fileOps),
            fileProvider = DriveFileProvider(http, credentialsManager, DriveFileProviderCached(http, credentialsManager, fileOps)),
            operationsProvider = DriveFileOperationsProvider(http, credentialsManager),
            reactionProvider = DriveFileGroupReactionProvider(http, credentialsManager),
            databaseManager = dbm,
            credentialsManager = credentialsManager,
        )
    }

    private suspend fun createRow(plaintext: String): Outbox {
        val request = UploadFileRequest(driveId = driveId, keyHeader = clientKey, metadata = metadata(plaintext))
        return Outbox(
            rowId = 1L, driveId = driveId, uniqueId = uniqueId, dependencyUniqueId = null, priority = 0L,
            lastAttempt = 0L, nextRunTime = 0L, checkOutCount = 1L, checkOutStamp = 1L,
            uploadType = DriveOutboxUploader.UploadNewFile,
            json = OdinSystemSerializer.serialize(request).encodeToByteArray(),
            files = null,
        )
    }

    @Test
    fun aLandedTextCreateWhoseReplyWasLostCompletesAsSent() = runTest {
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(OdinId("owner.test"), "test-token", SecureByteArray(secret.copyOf()))
        )
        val landed = metadata("hello").appData.content
        // Completing normally is the "sent" outcome; any throw drops the row as failed.
        uploader(serverHeaderBody(clientKey, landed)).upload(createRow("hello"), EventBus())
        assertEquals(0, updatesSent, "the landed create must not be replayed as an update")
    }
}
