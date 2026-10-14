package id.homebase.api.client.drives.files

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.FileIdFileIdentifier
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.client.CryptoHelper
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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Archive writes `archivedAt` into localAppData content, then queues the tag
 * change. The content write bumps the file's localAppData versionTag first, so
 * a tags row pinned to its enqueue-time tag got VersionTagMismatch, was dropped
 * as permanent, and the conversation reappeared unarchived.
 */
class DriveOutboxUploaderLocalTagsVersionTest {

    private val driveId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val fileId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val conversationId = Uuid.parse("dddddddd-dddd-dddd-dddd-dddddddddddd")
    private val sharedSecret = ByteArray(16)

    private val enqueueTimeVersionTag = Uuid.parse("85f62319-3049-cc00-c4b6-e3a19deaaf3f")
    private val afterContentWriteVersionTag = Uuid.parse("9052121a-1026-a200-4db6-d29fad8f1fa6")

    private val sentVersionTags = mutableListOf<String?>()

    private val mockEngine = MockEngine { request ->
        val (body, status) = when {
            request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/by-uid/$conversationId/header") ->
                OdinSystemSerializer.serialize(serverHeader()) to HttpStatusCode.OK

            request.method == HttpMethod.Patch && request.url.encodedPath.endsWith("/update-local-metadata-tags") -> {
                val encrypted = (request.body as TextContent).text
                val sent = OdinSystemSerializer.json
                    .parseToJsonElement(CryptoHelper.decryptContentAsString(encrypted, sharedSecret))
                    .jsonObject["localVersionTag"]?.jsonPrimitive?.content
                sentVersionTags += sent
                if (sent == afterContentWriteVersionTag.toString()) {
                    """{"newLocalVersionTag":"${Uuid.random()}"}""" to HttpStatusCode.OK
                } else {
                    """{"status":400,"title":"Mismatching version tag $afterContentWriteVersionTag","extensions":{"errorCode":"versionTagMismatch"}}""" to
                        HttpStatusCode.BadRequest
                }
            }

            else -> error("unexpected ${request.method} ${request.url}")
        }
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val httpClient = HttpClient(mockEngine)
    private val credentialsManager = CredentialsManager()
    private lateinit var dbm: DatabaseManager

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

    @BeforeTest
    fun setup() = runBlocking {
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("owner.test"),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(sharedSecret),
            )
        )
        dbm = DatabaseManager({ createInMemoryDatabase() })
    }

    @AfterTest
    fun tearDown() {
        dbm.close()
        httpClient.close()
    }

    private fun uploader(): DriveOutboxUploader {
        val driveCache = DriveFileProviderCached(httpClient, credentialsManager, fileOps)
        return DriveOutboxUploader(
            driveUploadProvider = DriveUploadProvider(httpClient, credentialsManager, fileOps),
            fileProvider = DriveFileProvider(httpClient, credentialsManager, driveCache),
            operationsProvider = DriveFileOperationsProvider(httpClient, credentialsManager),
            reactionProvider = DriveFileGroupReactionProvider(httpClient, credentialsManager),
            databaseManager = dbm,
            credentialsManager = credentialsManager,
        )
    }

    private fun serverHeader() = ServerFile(
        fileId = fileId,
        driveId = driveId,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        sharedSecretEncryptedKeyHeader = EncryptedKeyHeader.empty(),
        fileMetadata = FileMetadata(
            appData = AppFileMetaData(uniqueId = conversationId),
            localAppData = LocalAppMetadata(versionTag = afterContentWriteVersionTag),
        ),
        serverMetadata = ServerMetadata(),
    )

    private fun tagsRow(queuedVersionTag: String?) = Outbox(
        rowId = 1L,
        driveId = driveId,
        uniqueId = Uuid.random(),
        dependencyUniqueId = null,
        priority = 100,
        lastAttempt = 0,
        nextRunTime = 0,
        checkOutCount = 0,
        checkOutStamp = null,
        uploadType = DriveOutboxUploader.UpdateLocalMetadataTags,
        json = OdinSystemSerializer.serialize(
            UpdateLocalMetadataTagsOutboxRequest(
                file = FileIdFileIdentifier(fileId = fileId.toString(), targetDrive = TargetDrive(driveId, driveId)),
                versionTag = queuedVersionTag,
                tags = listOf(Uuid.random().toString()),
                uniqueId = conversationId,
            )
        ).encodeToByteArray(),
        files = null,
    )

    @Test
    fun `a tags row queued before a content write still lands`() = runTest {
        uploader().upload(tagsRow(queuedVersionTag = enqueueTimeVersionTag.toString()), EventBus())

        assertEquals(listOf<String?>(afterContentWriteVersionTag.toString()), sentVersionTags)
    }
}
