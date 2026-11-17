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
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.api.video.VideoPayloadProcessor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/** Who deletes an upload's files, on real files: the outbox once a row takes them, the service otherwise. */
class UploadServiceFilesTest {
    private val jvm = JvmFileOperationsProvider()
    private val made = mutableListOf<String>()
    private val fileOps: FileOperationsProvider = object : FileOperationsProvider by jvm {
        override suspend fun createOutboxStagingPath(prefix: String, suffix: String): String =
            jvm.createOutboxStagingPath(prefix, suffix).also { made += it }
    }
    private val workDir = File.createTempFile("upload-files", "test").let { it.delete(); it.mkdirs(); it }
    private val db = DatabaseManager({
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OdinDatabase.Schema.create(it) }
    })
    private val driveId = Uuid.random()
    private val encryptor = PayloadBundleEncryptionService(fileOps, VideoPayloadProcessor(fileOps), EventBus())

    @AfterTest
    fun close() {
        db.close()
        made.forEach { File(it).delete() }
        workDir.deleteRecursively()
    }

    private fun CoroutineScope.service(seeder: PayloadCacheSeeder = UnseedRecorder(fileOps)) = UploadService(
        encryptor = encryptor,
        outboxSync = OutboxSync(db, OfflineUploader, EventBus(), this).also { it.setOnline(false) },
        optimisticWriter = NoOptimisticRows,
        payloadCacheSeeder = seeder,
    )

    private fun source(name: String) = File(workDir, name).apply { writeText("the file $name") }.path

    private fun bundleOf(vararg payloads: PayloadFile) = PayloadBundle(payloads.toList(), emptyList(), emptyList())

    private fun plain(path: String) = PayloadFile(key = "pdf_0", filePath = path, contentType = "application/pdf")

    private suspend fun handedOverEncrypted(): PayloadFile =
        PayloadFile(key = "enc_0", filePath = jvm.createOutboxStagingPath("handed", ".encrypted"), contentType = "application/pdf", isPreEncrypted = true, iv = ByteArray(16))
            .also { File(it.filePath).writeText("ciphertext") }

    private fun metadata(uniqueId: Uuid, encrypted: Boolean = true, versionTag: Uuid? = null) = UploadFileMetadata(
        allowDistribution = false,
        isEncrypted = encrypted,
        appData = UploadAppFileMetaData(uniqueId = uniqueId, fileType = 1, userDate = 0),
        versionTag = versionTag,
    )

    private fun spec(uniqueId: Uuid, bundle: PayloadBundle, encrypted: Boolean = true, seedCache: Boolean = false) = MediaUploadSpec(
        driveId = driveId, uniqueId = uniqueId, keyHeader = KeyHeader.newRandom16(), bundle = bundle,
        metadata = metadata(uniqueId, encrypted), writeOptimistic = false, seedCache = seedCache, sendNow = false,
    )

    private fun exists(path: String) = File(path).exists()

    @Test
    fun aQueuedUploadLeavesItsFileForTheOutbox() = runTest {
        val uid = Uuid.random()

        assertIs<UploadOutcome.Enqueued>(backgroundScope.service().upload(spec(uid, bundleOf(plain(source("a.pdf")))), backgroundScope))

        assertTrue(exists(made.single()))
    }

    @Test
    fun anUploadTheOutboxAlreadyHoldsDeletesTheFileItEncrypted() = runTest {
        val uid = Uuid.random()
        val service = backgroundScope.service()
        assertIs<UploadOutcome.Enqueued>(service.upload(spec(uid, bundleOf(plain(source("a.pdf")))), backgroundScope))

        assertIs<UploadOutcome.AlreadyQueued>(service.upload(spec(uid, bundleOf(plain(source("b.pdf")))), backgroundScope))

        assertEquals(2, made.size)
        assertTrue(exists(made[0]), "the queued row's file stays")
        assertFalse(exists(made[1]), "the refused upload's file is deleted")
    }

    @Test
    fun aRefusedUploadDeletesAPayloadItWasHandedEncrypted() = runTest {
        val uid = Uuid.random()
        val service = backgroundScope.service()
        assertIs<UploadOutcome.Enqueued>(service.upload(spec(uid, bundleOf(plain(source("a.pdf")))), backgroundScope))
        val handed = handedOverEncrypted()

        assertIs<UploadOutcome.AlreadyQueued>(service.upload(spec(uid, bundleOf(handed)), backgroundScope))

        assertFalse(exists(handed.filePath))
    }

    @Test
    fun aPrepareThatFailsDeletesAPayloadItWasHandedEncrypted() = runTest {
        val handed = handedOverEncrypted()
        val missing = plain(File(workDir, "gone.pdf").path)

        val outcome = backgroundScope.service().upload(spec(Uuid.random(), bundleOf(handed, missing)), backgroundScope)

        assertIs<UploadOutcome.SourceMissing>(outcome)
        assertFalse(exists(handed.filePath))
    }

    @Test
    fun anUploadCancelledBeforeItsQueuedDeletesItsFilesAndUndoesTheSeed() = runTest {
        val uid = Uuid.random()
        val seeder = UnseedRecorder(fileOps, cancelOnSeed = true)
        val upload = spec(uid, bundleOf(plain(source("a.pdf"))), seedCache = true)

        assertFailsWith<CancellationException> { backgroundScope.service(seeder).upload(upload, backgroundScope) }

        assertFalse(exists(made.single()))
        assertNull(db.outbox.selectByDriveAndUnique(driveId, uid))
        assertEquals(listOf(upload.optimisticFileId), seeder.unseeded)
    }

    @Test
    fun aRefusedPublicUploadDeletesItsStagedCopyAndNeverTheSource() = runTest {
        val uid = Uuid.random()
        val service = backgroundScope.service()
        assertIs<UploadOutcome.Enqueued>(service.upload(spec(uid, bundleOf(plain(source("a.pdf"))), encrypted = false), backgroundScope))
        val second = source("b.pdf")

        assertIs<UploadOutcome.AlreadyQueued>(service.upload(spec(uid, bundleOf(plain(second)), encrypted = false), backgroundScope))

        assertFalse(exists(made[1]))
        assertTrue(exists(second), "the person's own file is never the upload's")
    }

    private fun update(uniqueId: Uuid, bundle: PayloadBundle? = null, handed: List<PayloadFile>? = null, replace: Boolean = false) = MediaUpdateSpec(
        driveId = driveId, uniqueId = uniqueId, keyHeader = KeyHeader.newRandom16(),
        metadata = metadata(uniqueId, versionTag = Uuid.random()),
        bundle = bundle, preEncryptedPayloads = handed, writeOptimistic = false, sendNow = false, replace = replace,
    )

    @Test
    fun aRefusedUpdateDeletesWhatItEncrypted() = runTest {
        val uid = Uuid.random()
        val service = backgroundScope.service()
        assertIs<UploadOutcome.Enqueued>(service.updateFile(update(uid, bundleOf(plain(source("a.pdf")))), backgroundScope))

        assertIs<UploadOutcome.AlreadyQueued>(service.updateFile(update(uid, bundleOf(plain(source("b.pdf")))), backgroundScope))

        assertTrue(exists(made[0]))
        assertFalse(exists(made[1]))
    }

    @Test
    fun aRefusedUpdateDeletesTheCopiesItWasHanded() = runTest {
        val uid = Uuid.random()
        val service = backgroundScope.service()
        val first = handedOverEncrypted()
        assertIs<UploadOutcome.Enqueued>(service.updateFile(update(uid, handed = listOf(first)), backgroundScope))
        val second = handedOverEncrypted()

        assertIs<UploadOutcome.AlreadyQueued>(service.updateFile(update(uid, handed = listOf(second)), backgroundScope))

        assertTrue(exists(first.filePath))
        assertFalse(exists(second.filePath))
    }

    @Test
    fun anUpdateThatMayNotReplaceAPendingCreateDeletesItsFiles() = runTest {
        val uid = Uuid.random()
        val service = backgroundScope.service()
        assertIs<UploadOutcome.Enqueued>(service.upload(spec(uid, bundleOf(plain(source("a.pdf")))), backgroundScope))

        assertIs<UploadOutcome.WouldStrandCreate>(service.updateFile(update(uid, bundleOf(plain(source("b.pdf"))), replace = true), backgroundScope))

        assertTrue(exists(made[0]))
        assertFalse(exists(made[1]))
    }

    @Test
    fun discardRemovesAVideosHlsFolder() = runTest {
        val hls = File(workDir, "hls_${Uuid.random()}").apply { mkdirs() }
        val segments = File(hls, "index.ts").apply { writeText("ts") }
        File(hls, "index.m3u8").writeText("m3u8")

        encryptor.discard(bundleOf(PayloadFile(key = "vid_0", filePath = segments.path, contentType = "video/mp2t", isPreEncrypted = true)))

        assertFalse(hls.exists())
    }
}

private object OfflineUploader : OutboxUploader {
    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) = error("the outbox is offline in these tests")
}

private object NoOptimisticRows : OptimisticLocalWriter {
    override suspend fun writeNewFile(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        originalRecipientCount: Int,
        fileSystemType: FileSystemType,
        payloadDescriptors: List<PayloadDescriptor>?,
        fileId: Uuid,
    ): Uuid = fileId

    override suspend fun writeUpdate(driveId: Uuid, keyHeader: KeyHeader, unecryptedMetadata: UploadFileMetadata, payloadDescriptors: List<PayloadDescriptor>?) = Unit

    override suspend fun removeOptimisticFile(driveId: Uuid, uniqueId: Uuid): Boolean = true
}

private class UnseedRecorder(fileOps: FileOperationsProvider, private val cancelOnSeed: Boolean = false) : PayloadCacheSeeder(
    HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }).let { http ->
        DriveFileProvider(http, CredentialsManager(), DriveFileProviderCached(http, CredentialsManager(), fileOps))
    },
    fileOps,
) {
    val unseeded = mutableListOf<Uuid>()

    override suspend fun seed(driveId: Uuid, fileId: Uuid, bundle: PayloadBundle, encrypted: Boolean) {
        if (cancelOnSeed) throw CancellationException("stopped while seeding")
    }

    override suspend fun unseed(driveId: Uuid, fileId: Uuid, bundle: PayloadBundle) {
        unseeded += fileId
    }
}
