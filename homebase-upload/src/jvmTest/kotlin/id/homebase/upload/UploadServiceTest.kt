package id.homebase.upload

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.SourceUnavailableException
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Unit tests for the shared upload spine ([UploadService]) — the piece PR2/PR3/PR3.5 built the
 * whole #844 series on, and which was previously only exercised indirectly via the chat sender.
 *
 * Focus (the surface with no other coverage):
 *   - `upload` create path: durable enqueue + optimistic-write correlation, and the Deliverable-A
 *     invariant that a missing source enqueues NOTHING.
 *   - `updateFile` (PR3.5) all shapes: header-only, new-payload (encrypts), and re-attaching a
 *     file's already-encrypted payloads (must SKIP encryption).
 *
 * Wiring mirrors `ChatMessageSenderServiceTestFixture`: a real in-memory SQLite DB + real
 * [OutboxSync] with `setOnline(false)`, so enqueues are real and inspectable via `outbox.count()`.
 * The encryptor and optimistic-writer are controllable fakes so we can assert call counts and
 * force the swept-source path; the cache seeder is real but never invoked (`seedCache = false`).
 */
class UploadServiceTest {

    private var dbm: DatabaseManager? = null

    @AfterTest
    fun tearDown() {
        dbm?.close()
    }

    // ---------- fixture ----------

    private class Harness(
        val service: UploadService,
        val dbm: DatabaseManager,
        val encryptor: RecordingEncryptor,
        val writer: RecordingOptimisticWriter,
    )

    private suspend fun countRows(h: Harness): Long = h.dbm.outbox.count()

    private suspend fun buildHarness(
        scope: CoroutineScope,
        encryptor: RecordingEncryptor = RecordingEncryptor(),
    ): Harness {
        val db = DatabaseManager({
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            OdinDatabase.Schema.create(driver)
            driver
        })
        dbm = db
        val eventBus = EventBus()
        val outbox = OutboxSync(
            databaseManager = db,
            uploader = ThrowingUploader,
            eventBus = eventBus,
            scope = scope,
        ).also { it.setOnline(false) }

        // Real seeder, never invoked (every spec uses seedCache = false); constructing it only
        // needs a DriveFileProvider, wired over a MockEngine that 500s (never called).
        val httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        val creds = CredentialsManager()
        val seeder = PayloadCacheSeeder(
            DriveFileProvider(httpClient, creds, DriveFileProviderCached(httpClient, creds, NoopFileOps())),
            NoopFileOps(),
        )
        val writer = RecordingOptimisticWriter()
        val service = UploadService(
            encryptor = encryptor,
            outboxSync = outbox,
            optimisticWriter = writer,
            payloadCacheSeeder = seeder,
        )
        return Harness(service, db, encryptor, writer)
    }

    private val driveId = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")

    private fun metadata(uniqueId: Uuid, versionTag: Uuid? = null) = UploadFileMetadata(
        allowDistribution = false,
        isEncrypted = true,
        appData = UploadAppFileMetaData(uniqueId = uniqueId, fileType = 1, userDate = 0),
        versionTag = versionTag,
    )

    private fun payload(key: String) = PayloadFile(key = key, filePath = "src/$key", contentType = "image/jpeg")

    private fun bundleOf(vararg keys: String) =
        PayloadBundle(payloads = keys.map { payload(it) }, thumbnails = emptyList(), previewThumbs = emptyList())

    // ---------- upload (create) ----------

    @Test
    fun upload_happyPath_enqueuesOneRow_andWritesOptimisticWithSeededId() = runTest {
        val h = buildHarness(backgroundScope)
        val uid = Uuid.random()
        val fileId = Uuid.random()
        val outcome = h.service.upload(
            MediaUploadSpec(
                driveId = driveId, uniqueId = uid, keyHeader = KeyHeader.newRandom16(),
                bundle = bundleOf("p0"), metadata = metadata(uid),
                optimisticFileId = fileId, writeOptimistic = true, seedCache = false, sendNow = false,
            ),
            scope = backgroundScope,
        )
        val enqueued = assertIs<UploadOutcome.Enqueued>(outcome)
        assertEquals(fileId, enqueued.optimisticFileId)
        assertEquals(1L, countRows(h), "exactly one outbox row")
        assertEquals(listOf(fileId), h.writer.newFileIds, "optimistic writeNewFile with the seeded id")
        assertEquals(0, h.writer.updateCount)
    }

    @Test
    fun upload_missingSource_enqueuesNothing_andSkipsOptimisticWrite() = runTest {
        val h = buildHarness(backgroundScope, RecordingEncryptor(throwMissing = "src/p0"))
        val uid = Uuid.random()
        val outcome = h.service.upload(
            MediaUploadSpec(
                driveId = driveId, uniqueId = uid, keyHeader = KeyHeader.newRandom16(),
                bundle = bundleOf("p0"), metadata = metadata(uid),
                writeOptimistic = true, seedCache = false, sendNow = false,
            ),
            scope = backgroundScope,
        )
        val missing = assertIs<UploadOutcome.SourceMissing>(outcome)
        assertEquals(listOf("src/p0"), missing.missingPaths)
        assertEquals(0L, countRows(h), "Deliverable A: a swept source enqueues no doomed row")
        assertTrue(h.writer.newFileIds.isEmpty(), "no optimistic write when nothing was enqueued")
    }

    @Test
    fun upload_writeOptimisticFalse_stillEnqueues_withoutOptimisticWrite() = runTest {
        // Moments' two-phase post uses this mode (the feature promotes its own placeholder).
        val h = buildHarness(backgroundScope)
        val uid = Uuid.random()
        val outcome = h.service.upload(
            MediaUploadSpec(
                driveId = driveId, uniqueId = uid, keyHeader = KeyHeader.newRandom16(),
                bundle = bundleOf("p0"), metadata = metadata(uid),
                writeOptimistic = false, seedCache = false, sendNow = false,
            ),
            scope = backgroundScope,
        )
        val enqueued = assertIs<UploadOutcome.Enqueued>(outcome)
        assertNull(enqueued.optimisticFileId, "no optimistic id when writeOptimistic = false")
        assertEquals(1L, countRows(h))
        assertTrue(h.writer.newFileIds.isEmpty())
    }

    // ---------- updateFile (PR3.5) ----------

    @Test
    fun updateFile_headerOnly_enqueuesOneRow_andWritesUpdate() = runTest {
        val h = buildHarness(backgroundScope)
        val uid = Uuid.random()
        val outcome = h.service.updateFile(
            MediaUpdateSpec(
                driveId = driveId, uniqueId = uid, keyHeader = KeyHeader.newRandom16(),
                metadata = metadata(uid, versionTag = Uuid.random()),
                bundle = null, writeOptimistic = true,
            ),
            scope = backgroundScope,
        )
        assertIs<UploadOutcome.Enqueued>(outcome)
        assertEquals(1L, countRows(h))
        assertEquals(1, h.writer.updateCount, "header-only update writes an optimistic writeUpdate")
    }

    @Test
    fun updateFile_reusedPreEncryptedPayloads_skipsEncryption() = runTest {
        // add-recipients re-attaches a file's EXISTING encrypted payloads — must not re-encrypt.
        val h = buildHarness(backgroundScope)
        val uid = Uuid.random()
        val outcome = h.service.updateFile(
            MediaUpdateSpec(
                driveId = driveId, uniqueId = uid, keyHeader = KeyHeader.newRandom16(),
                metadata = metadata(uid, versionTag = Uuid.random()),
                preEncryptedPayloads = listOf(payload("p0").copy(isPreEncrypted = true)),
                writeOptimistic = false,
            ),
            scope = backgroundScope,
        )
        assertIs<UploadOutcome.Enqueued>(outcome)
        assertEquals(1L, countRows(h))
        assertEquals(0, h.encryptor.callCount, "reused payloads must bypass the encryptor entirely")
    }

    @Test
    fun updateFile_newPayload_encryptsAndEnqueues() = runTest {
        val h = buildHarness(backgroundScope)
        val uid = Uuid.random()
        val outcome = h.service.updateFile(
            MediaUpdateSpec(
                driveId = driveId, uniqueId = uid, keyHeader = KeyHeader.newRandom16(),
                metadata = metadata(uid, versionTag = Uuid.random()),
                bundle = bundleOf("p0"), writeOptimistic = false,
            ),
            scope = backgroundScope,
        )
        assertIs<UploadOutcome.Enqueued>(outcome)
        assertEquals(1L, countRows(h))
        assertEquals(1, h.encryptor.callCount, "a new plaintext payload is encrypted by the pipeline")
    }

    @Test
    fun updateFile_missingSource_enqueuesNothing() = runTest {
        val h = buildHarness(backgroundScope, RecordingEncryptor(throwMissing = "src/p0"))
        val uid = Uuid.random()
        val outcome = h.service.updateFile(
            MediaUpdateSpec(
                driveId = driveId, uniqueId = uid, keyHeader = KeyHeader.newRandom16(),
                metadata = metadata(uid, versionTag = Uuid.random()),
                bundle = bundleOf("p0"), writeOptimistic = false,
            ),
            scope = backgroundScope,
        )
        assertIs<UploadOutcome.SourceMissing>(outcome)
        assertEquals(0L, countRows(h))
    }
}

// ---------- test doubles ----------

/** Fake encryptor: counts calls, echoes the input as "pre-encrypted", or throws a swept source. */
private class RecordingEncryptor(private val throwMissing: String? = null) : PayloadBundleEncryptor {
    var callCount = 0
        private set

    override suspend fun encryptBundle(
        uniqueId: Uuid,
        bundle: PayloadBundle?,
        aesKey: SecureByteArray,
        scope: CoroutineScope,
    ): PayloadBundle {
        callCount++
        throwMissing?.let { throw SourceUnavailableException(it) }
        return PayloadBundle(
            payloads = bundle?.payloads?.map { it.copy(isPreEncrypted = true) } ?: emptyList(),
            thumbnails = emptyList(),
            previewThumbs = emptyList(),
        )
    }
}

/** Records the optimistic writes the pipeline performs. */
private class RecordingOptimisticWriter : OptimisticLocalWriter {
    val newFileIds = mutableListOf<Uuid>()
    var updateCount = 0
        private set

    override suspend fun writeNewFile(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        originalRecipientCount: Int,
        fileSystemType: FileSystemType,
        payloadDescriptors: List<PayloadDescriptor>?,
        fileId: Uuid,
    ): Uuid {
        newFileIds += fileId
        return fileId
    }

    override suspend fun writeUpdate(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        payloadDescriptors: List<PayloadDescriptor>?,
    ) {
        updateCount++
    }

    override suspend fun removeOptimisticFile(driveId: Uuid, uniqueId: Uuid): Boolean = true
}

private object ThrowingUploader : OutboxUploader {
    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) =
        error("OutboxUploader.upload must not run (setOnline(false))")
}

/**
 * No file IO is expected during a test — the seeder is never invoked (seedCache = false). Only
 * [getCacheDirectory] returns a real path, because DriveFileProviderCached reads it eagerly at
 * construction time to set up its (unused) Coil disk caches.
 */
private class NoopFileOps : FileOperationsProvider {
    private val cacheDir: String = java.nio.file.Files.createTempDirectory("hb-upload-test-cache").toString()
    private fun nope(): Nothing = error("no file IO expected in UploadServiceTest")
    override fun openFileInput(path: String) = nope()
    override suspend fun readFileBytes(path: String) = nope()
    override fun deleteTempFile(path: String) = nope()
    override fun getCacheDirectory(): String = cacheDir
    override fun getFileSize(path: String) = nope()
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String) = nope()
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String) = nope()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = nope()
}
