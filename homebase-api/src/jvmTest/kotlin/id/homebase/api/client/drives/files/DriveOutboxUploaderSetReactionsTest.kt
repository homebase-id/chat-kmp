package id.homebase.api.client.drives.files

import id.homebase.api.client.ClientException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.SetReactionsOutboxRequest
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * [DriveOutboxUploader.setReactions]: a set-state row deletes before it
 * adds, and treats the server's "duplicate reaction" 400 as already applied so
 * a retried row converges instead of failing on what an earlier attempt landed.
 */
class DriveOutboxUploaderSetReactionsTest {

    private val driveId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val fileId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    private val calls = mutableListOf<HttpMethod>()
    private var addResponder: () -> Pair<String, HttpStatusCode> = { "{}" to HttpStatusCode.OK }

    private val mockEngine = MockEngine { request ->
        calls += request.method
        val (body, status) = when (request.method) {
            HttpMethod.Delete -> "{}" to HttpStatusCode.OK
            HttpMethod.Post -> addResponder()
            else -> error("unexpected ${request.method}")
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
                sharedSecret = SecureByteArray(ByteArray(16)),
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

    private fun row(add: List<String>, remove: List<String>) = Outbox(
        rowId = 1L,
        driveId = driveId,
        uniqueId = Uuid.random(),
        dependencyUniqueId = null,
        priority = 100,
        lastAttempt = 0,
        nextRunTime = 0,
        checkOutCount = 0,
        checkOutStamp = null,
        uploadType = DriveOutboxUploader.SetReactions,
        json = OdinSystemSerializer.serialize(
            SetReactionsOutboxRequest(
                driveId = driveId,
                fileId = fileId,
                add = add,
                remove = remove,
                recipients = listOf(OdinId("frodo.test")),
            )
        ).encodeToByteArray(),
        files = null,
    )

    private fun duplicateReaction400(): Pair<String, HttpStatusCode> =
        """{"status":400,"title":"Cannot add duplicate reaction","extensions":{"errorCode":"UnhandledScenario"}}""" to
            HttpStatusCode.BadRequest

    @Test
    fun deletes_run_before_adds() = runTest {
        uploader().upload(row(add = listOf("""{"emoji":"✅"}"""), remove = listOf("""{"emoji":"🤔"}""", """{"emoji":"❌"}""")), EventBus())

        assertEquals(listOf(HttpMethod.Delete, HttpMethod.Delete, HttpMethod.Post), calls)
    }

    @Test
    fun duplicate_add_is_treated_as_applied() = runTest {
        addResponder = ::duplicateReaction400

        uploader().upload(row(add = listOf("""{"emoji":"✅"}"""), remove = listOf("""{"emoji":"🤔"}""")), EventBus())

        assertEquals(listOf(HttpMethod.Delete, HttpMethod.Post), calls)
    }

    @Test
    fun other_add_failures_still_fail_the_row() = runTest {
        addResponder = {
            """{"status":400,"title":"Too many Reactions","extensions":{"errorCode":"UnhandledScenario"}}""" to
                HttpStatusCode.BadRequest
        }

        assertFailsWith<ClientException> {
            uploader().upload(row(add = listOf("""{"emoji":"✅"}"""), remove = emptyList()), EventBus())
        }
    }
}
