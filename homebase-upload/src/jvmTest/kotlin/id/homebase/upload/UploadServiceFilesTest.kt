package id.homebase.upload

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.crypto.newHasher
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.video.VideoPayloadProcessor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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

    private val handedPaths = mutableListOf<String>()
    private val clients = mutableListOf<HttpClient>()

    @AfterTest
    fun close() {
        db.close()
        (made + handedPaths).forEach { File(it).delete() }
        clients.forEach { it.close() }
        workDir.deleteRecursively()
    }

    private fun seeder(holdOnSeed: Boolean = false) = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }).let { http ->
        clients += http
        RecordingSeeder(DriveFileProvider(http, CredentialsManager(), DriveFileProviderCached(http, CredentialsManager(), fileOps)), fileOps, holdOnSeed)
    }

    private fun CoroutineScope.service(seeder: PayloadCacheSeeder = seeder()) = UploadService(
        encryptor = encryptor,
        outboxSync = OutboxSync(db, ThrowingUploader, EventBus(), this).also { it.setOnline(false) },
        optimisticWriter = RecordingOptimisticWriter(),
        payloadCacheSeeder = seeder,
    )

    private fun source(name: String) = File(workDir, name).apply { writeText("the file $name") }.path

    private fun bundleOf(vararg payloads: PayloadFile) = PayloadBundle(payloads.toList(), emptyList(), emptyList())

    private fun plain(path: String) = PayloadFile(key = "pdf_0", filePath = path, contentType = "application/pdf")

    private suspend fun handedOverEncrypted(iv: ByteArray? = ByteArray(16)): PayloadFile =
        PayloadFile(key = "enc_0", filePath = jvm.createOutboxStagingPath("handed", ".encrypted"), contentType = "application/pdf", isPreEncrypted = true, iv = iv)
            .also { File(it.filePath).writeText("ciphertext"); handedPaths += it.filePath }

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

    private fun blake3(path: String): ByteArray = PayloadHashAlgorithm.Blake3.newHasher().run { update(File(path).readBytes()); digest() }

    @Test
    fun aPayloadThatNoLongerMatchesItsHashIsRefusedWithNothingLeft() = runTest {
        val first = plain(source("a.pdf"))
        val second = plain(source("b.pdf")).copy(key = "pdf_1")
        val hashes = ContentHashes(PayloadHashAlgorithm.Blake3, mapOf(first.key to blake3(first.filePath), second.key to ByteArray(32)))

        assertFailsWith<ContentChangedException> {
            backgroundScope.service().prepare(spec(Uuid.random(), bundleOf(first, second)).copy(contentHashes = hashes), backgroundScope)
        }

        assertEquals(2, made.size)
        assertTrue(made.none(::exists), "the one that matched is deleted too")
        assertTrue(exists(first.filePath) && exists(second.filePath), "the sources are the caller's")
    }

    @Test
    fun aPayloadThatStillMatchesItsHashIsSentWithIt() = runTest {
        val payload = plain(source("a.pdf"))
        val spec = spec(Uuid.random(), bundleOf(payload)).copy(
            contentHashes = ContentHashes(PayloadHashAlgorithm.Blake3, mapOf(payload.key to blake3(payload.filePath))),
        )

        val request = backgroundScope.service().prepare(spec, backgroundScope).request

        val sent = request.payloads.single()
        assertContentEquals(blake3(payload.filePath), sent.hash!!.plaintextContentHash(KeyHeader(sent.iv!!, spec.keyHeader.aesKey)))
        assertEquals(spec.metadata.encryptContent(spec.keyHeader).appData.content, request.metadata.appData.content)
    }

    @Test
    fun anUploadThatAsksForNoHashesSendsNone() = runTest {
        val request = backgroundScope.service().prepare(spec(Uuid.random(), bundleOf(plain(source("a.pdf")))), backgroundScope).request

        assertNull(request.payloads.single().hash)
    }

    @Test
    fun aVideoToTranscodeCantBeCheckedAgainstItsSourcesHash() = runTest {
        val video = PayloadFile(key = "vid_0", filePath = source("v.mp4"), contentType = "video/mp4")
        val hashes = ContentHashes(PayloadHashAlgorithm.Blake3, mapOf(video.key to blake3(video.filePath)))

        val outcome = backgroundScope.service().upload(spec(Uuid.random(), bundleOf(video)).copy(contentHashes = hashes), backgroundScope)

        assertIs<IllegalArgumentException>(assertIs<UploadOutcome.Failed>(outcome).cause)
        assertTrue(made.isEmpty())
    }

    @Test
    fun aHashThatCantBeCheckedIsRefusedNotSkipped() = runTest {
        val pdf = plain(source("a.pdf"))
        for (key in listOf("no_such_payload", "enc_0")) {
            val handed = handedOverEncrypted()
            val hashes = ContentHashes(PayloadHashAlgorithm.Blake3, mapOf(key to ByteArray(32)))

            val outcome = backgroundScope.service().upload(spec(Uuid.random(), bundleOf(pdf, handed)).copy(contentHashes = hashes), backgroundScope)

            assertIs<IllegalArgumentException>(assertIs<UploadOutcome.Failed>(outcome).cause, key)
            assertFalse(exists(handed.filePath))
        }
        assertTrue(made.isEmpty())
    }

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
    fun anUploadCancelledWhileSeedingDeletesItsFilesAndUndoesTheSeed() = runTest {
        val uid = Uuid.random()
        val seeder = seeder(holdOnSeed = true)
        val upload = spec(uid, bundleOf(plain(source("a.pdf"))), seedCache = true)
        val service = backgroundScope.service(seeder)
        val sending = backgroundScope.launch { service.upload(upload, backgroundScope) }
        seeder.seeding.await()

        sending.cancelAndJoin()

        assertFalse(exists(made.single()))
        assertNull(db.outbox.selectByDriveAndUnique(driveId, uid))
        assertEquals(listOf(upload.optimisticFileId), seeder.unseededFileIds)
    }

    @Test
    fun aPreparedUploadIsQueuedOnceAndThenTheOutboxsToDelete() = runTest {
        val service = backgroundScope.service()
        val prepared = service.prepare(spec(Uuid.random(), bundleOf(plain(source("a.pdf")))), backgroundScope)
        assertIs<UploadOutcome.Enqueued>(service.queue(prepared))

        prepared.discard()

        assertTrue(exists(made.single()), "the row's file stays")
        assertFailsWith<IllegalStateException> { service.queue(prepared) }
    }

    @Test
    fun aDiscardedPreparedUploadIsGoneAndCantBeQueued() = runTest {
        val service = backgroundScope.service()
        val prepared = service.prepare(spec(Uuid.random(), bundleOf(plain(source("a.pdf")))), backgroundScope)

        prepared.discard()

        assertFalse(exists(made.single()))
        assertFailsWith<IllegalStateException> { service.queue(prepared) }
    }

    @Test
    fun aPayloadHandedOverEncryptedThatCantBeSentAsItIsIsRefusedAndDeleted() = runTest {
        val service = backgroundScope.service()
        val inAPublicUpload = handedOverEncrypted()
        val withoutItsIv = handedOverEncrypted(iv = null)

        assertIs<UploadOutcome.Failed>(service.upload(spec(Uuid.random(), bundleOf(inAPublicUpload), encrypted = false), backgroundScope))
        assertIs<UploadOutcome.Failed>(service.upload(spec(Uuid.random(), bundleOf(withoutItsIv)), backgroundScope))

        assertFalse(exists(inAPublicUpload.filePath))
        assertFalse(exists(withoutItsIv.filePath))
        assertTrue(made.isEmpty(), "nothing was encrypted or staged")
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
    fun anUpdateThatFailsDeletesAPayloadItWasHandedEncrypted() = runTest {
        val handedFile = handedOverEncrypted()

        val outcome = backgroundScope.service().updateFile(update(Uuid.random(), bundleOf(handedFile, plain(File(workDir, "gone.pdf").path))), backgroundScope)

        assertIs<UploadOutcome.SourceMissing>(outcome)
        assertFalse(exists(handedFile.filePath))
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
