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

/**
 * Shared scaffolding for the feed sender/comments/reaction tests: a real in-memory [OdinDatabase]
 * (so a real [OutboxSync] persists durable rows we can read back) plus a no-op uploader so nothing
 * actually hits the network. The JDBC SQLite driver is on the homebase-core jvmTest runtime
 * classpath transitively (sqlite-driver → jdbc-driver), so this needs no build-file change.
 */
class FeedTestEnv(
    testScope: TestScope,
    /**
     * Optional hook to wrap the in-memory driver before [DatabaseManager] opens it — lets a test
     * inject a failure (e.g. a refused `INSERT INTO Outbox`) so the enqueue-failure / rollback
     * branches of a service become reachable. Identity by default.
     */
    wrapDriver: (SqlDriver) -> SqlDriver = { it },
) {
    // The JDBC SQLite driver is on the jvmTest *runtime* classpath (sqlite-driver → jdbc-driver)
    // but NOT the compile classpath, so we instantiate it reflectively here rather than add a
    // build-file dependency. DatabaseManager runs OdinDatabase.Schema.create on the driver itself.
    val driver: SqlDriver = wrapDriver(newInMemoryJdbcDriver())

    // Bind the DB dispatchers to the test scheduler so advanceUntilIdle drains all DB work and the
    // outbox is quiescent before assertions (mirrors OutboxSyncTest.runOutboxTest).
    private val dbDispatcher = StandardTestDispatcher(testScope.testScheduler)
    val databaseManager = DatabaseManager(
        { driver },
        dispatcher = dbDispatcher,
        readDispatcher = dbDispatcher,
    )

    val eventBus = EventBus()
    val credentialsManager = CredentialsManager()
    val fileOps: FileOperationsProvider = InMemoryFileOps()

    /** A coroutine scope bound to the test scheduler, for services that launch background work. */
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

    /**
     * A real [UploadService] over the fixture's outbox / optimistic-writer / encryptor, so a
     * service migrated onto UploadService (issue #844) still enqueues into the same inspectable
     * in-memory outbox. Mirrors homebase-chat's `buildTestUploadService`: the cache seeder is
     * wired over a MockEngine that 500s (only reached by a payload-bearing send).
     */
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

    /**
     * A [DriveQueryProvider] over a MockEngine that 500s. The feed comment tests only cold-load OWN
     * posts (null senderOdinId), so the over-peer comment query is never reached — this exists just
     * to satisfy the [PostCommentsService] constructor.
     */
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

    /** The single pending outbox row for (driveId, uniqueId), or null. */
    suspend fun outboxRow(driveId: kotlin.uuid.Uuid, uniqueId: kotlin.uuid.Uuid): Outbox? =
        databaseManager.outbox.selectByDriveAndUnique(driveId, uniqueId)

    suspend fun outboxCount(): Long = databaseManager.outbox.count()
}

/**
 * Passthrough encryptor: returns the bundle verbatim so payload keys survive into the upload
 * request unchanged. The real encryptor is exercised in chat tests; here we only assert routing
 * (drive, fileType, groupId), not ciphertext.
 */
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

/**
 * Build an in-memory SQLite [SqlDriver] reflectively. `JdbcSqliteDriver` lives in
 * `app.cash.sqldelight:sqlite-driver`, which is on the jvmTest *runtime* classpath (transitively)
 * but not the compile classpath, so it can't be named directly without a build-file edit. The
 * one-arg `JdbcSqliteDriver(url)` constructor with the `IN_MEMORY` URL is all we need.
 */
private fun newInMemoryJdbcDriver(): SqlDriver {
    val cls = Class.forName("app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver")
    val ctor = cls.getConstructor(String::class.java, java.util.Properties::class.java)
    return ctor.newInstance("jdbc:sqlite:", java.util.Properties()) as SqlDriver
}

/** Minimal in-memory FileOperationsProvider: temp "files" are just synthetic paths. */
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
