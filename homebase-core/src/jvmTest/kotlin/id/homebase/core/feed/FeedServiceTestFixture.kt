package id.homebase.core.feed

import app.cash.sqldelight.db.SqlDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.chat.services.outbox.OptimisticWriterPort
import id.homebase.upload.PayloadBundle
import id.homebase.upload.PayloadBundleEncryptor
import id.homebase.upload.PayloadCacheSeeder
import id.homebase.upload.UploadService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import id.homebase.api.common.SecureByteArray as Sba

// A real in-memory OdinDatabase (so a real OutboxSync persists durable rows we can read back) plus a no-op
// uploader so nothing hits the network.
class FeedTestEnv(
    testScope: TestScope,
    /** Lets a test wrap the driver to inject a failure (e.g. a refused `INSERT INTO Outbox`). Identity by default. */
    wrapDriver: (SqlDriver) -> SqlDriver = { it },
) {
    // The JDBC SQLite driver is on the jvmTest *runtime* classpath but NOT the compile classpath, so it is
    // instantiated reflectively rather than adding a build-file dependency.
    val driver: SqlDriver = wrapDriver(newInMemoryJdbcDriver())

    // Bound to the test scheduler so advanceUntilIdle drains all DB work before assertions.
    private val dbDispatcher = StandardTestDispatcher(testScope.testScheduler)
    val databaseManager = DatabaseManager(
        { driver },
        dispatcher = dbDispatcher,
        readDispatcher = dbDispatcher,
    )

    val eventBus = EventBus()
    val credentialsManager = CredentialsManager()
    val fileOps: FileOperationsProvider = InMemoryFileOps()

    val scope: CoroutineScope = testScope.backgroundScope

    private val noopUploader = object : OutboxUploader {
        override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
                // Never invoked: the outbox is left offline so rows persist for inspection.
        }
    }

    val outboxSync = OutboxSync(
        databaseManager = databaseManager,
        uploader = noopUploader,
        eventBus = eventBus,
        scope = testScope.backgroundScope,
    )

    val optimisticWriter = OptimisticWriter(
        credentialsManager = credentialsManager,
        dbm = databaseManager,
        eventBus = eventBus,
        outboxSync = outboxSync,
    )

    val payloadBundleEncryptor: PayloadBundleEncryptor = PassthroughEncryptor()

    // The cache seeder is wired over a MockEngine that 500s — only reached by a payload-bearing send.
    val uploadService: UploadService = run {
        val http = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        val driveFileProvider = DriveFileProvider(
            httpClient = http,
            credentialsManager = credentialsManager,
            driveCache = DriveFileProviderCached(http, credentialsManager, fileOps),
        )
        UploadService(
            encryptor = payloadBundleEncryptor,
            outboxSync = outboxSync,
            optimisticWriter = OptimisticWriterPort(optimisticWriter),
            payloadCacheSeeder = PayloadCacheSeeder(driveFileProvider, fileOps),
        )
    }

    // Over a MockEngine that 500s: the feed comment tests only cold-load OWN posts, so the over-peer query is
    // never reached. This exists to satisfy the PostCommentsService constructor.
    val driveQueryProvider: DriveQueryProvider = DriveQueryProvider(
        httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }),
        credentialsManager = credentialsManager,
    )

    suspend fun login(domain: String = "test.example.com") {
        credentialsManager.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId(domain),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(16) { 1 }),
            )
        )
    }

    fun close() {
        databaseManager.close()
    }

    suspend fun outboxRow(driveId: kotlin.uuid.Uuid, uniqueId: kotlin.uuid.Uuid): Outbox? =
        databaseManager.outbox.selectByDriveAndUnique(driveId, uniqueId)

    suspend fun outboxCount(): Long = databaseManager.outbox.count()
}

// Returns the bundle verbatim so payload keys survive into the upload request unchanged; these tests assert
// routing (drive, fileType, groupId), not ciphertext.
private class PassthroughEncryptor : PayloadBundleEncryptor {
    override suspend fun encryptBundle(
        uniqueId: kotlin.uuid.Uuid,
        bundle: PayloadBundle?,
        aesKey: Sba,
        scope: CoroutineScope,
    ): PayloadBundle = bundle ?: PayloadBundle(
        payloads = emptyList(),
        thumbnails = emptyList(),
        previewThumbs = emptyList(),
    )
}

// JdbcSqliteDriver is on the jvmTest *runtime* classpath but not the compile classpath, so it can't be named
// directly without a build-file edit.
private fun newInMemoryJdbcDriver(): SqlDriver {
    val cls = Class.forName("app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver")
    val ctor = cls.getConstructor(String::class.java, java.util.Properties::class.java)
    return ctor.newInstance("jdbc:sqlite:", java.util.Properties()) as SqlDriver
}

private class InMemoryFileOps : FileOperationsProvider {
    private var counter = 0
    override fun getCacheDirectory(): String = "/tmp/feed-test"
    override fun openFileInput(path: String): InputProvider = throw UnsupportedOperationException()
    override suspend fun readFileBytes(path: String): ByteArray = ByteArray(0)
    override fun deleteTempFile(path: String): Boolean = true
    override fun getFileSize(path: String): Long = 0L
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String =
        "/tmp/feed-test/$prefix${counter++}$suffix"
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
        "/tmp/feed-test/share${counter++}$suffix"
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) {}
}
