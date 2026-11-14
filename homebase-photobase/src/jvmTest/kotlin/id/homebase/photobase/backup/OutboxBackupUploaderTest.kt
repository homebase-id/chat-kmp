package id.homebase.photobase.backup

import id.homebase.api.file.contentTypeFromBytes
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.Blake3Hasher
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.serialization.OutboxSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OUTBOX_BULK_PRIORITY
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.upload.OptimisticLocalWriter
import id.homebase.upload.PayloadBundle
import id.homebase.upload.PayloadBundleEncryptor
import id.homebase.upload.PayloadCacheSeeder
import id.homebase.upload.UploadService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class OutboxBackupUploaderTest {
    private val fileOps = JvmFileOperationsProvider()
    private val db = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
    private val workDir = File.createTempFile("photobase", "test").let { it.delete(); it.mkdirs(); it }
    private val writer = CountingWriter()

    @AfterTest
    fun close() {
        db.close()
        workDir.deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray) = File(workDir, name).apply { writeBytes(bytes) }.path

    private fun digest(bytes: ByteArray) = Blake3Hasher().apply { update(bytes) }.digest()

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun rowFor(id: String, mediaType: BackupMediaType, vararg parts: ByteArray): Pair<id.homebase.api.sync.database.DeviceBackupAsset, Uuid> {
        val ledger = BackupLedger(db.deviceBackup) { 1000 }
        ledger.apply(fullListing(asset(id, createdAtMs = 12345, mediaType = mediaType)), null)
        val hashed = HashedAsset(parts.map { digest(it) })
        ledger.markHashed(id, hashed.contentHash, hashed.uniqueId, 10)
        return ledger.row(id)!! to hashed.uniqueId
    }

    private class PartsSource(val parts: List<ExportedPart>) : DeviceChangeSource by FakeSource() {
        override suspend fun export(assetId: String, mediaType: BackupMediaType) = ExportedAsset(parts, "IMG_0001.HEIC")
    }

    private fun CoroutineScope.uploader(parts: List<ExportedPart>, encryptor: PayloadBundleEncryptor = NoEncryption): OutboxBackupUploader {
        val source = PartsSource(parts)
        val outbox = OutboxSync(db, NeverSends, EventBus(), this).also { it.setOnline(false) }
        val http = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        val creds = CredentialsManager()
        val seeder = PayloadCacheSeeder(DriveFileProvider(http, creds, DriveFileProviderCached(http, creds, fileOps)), fileOps)
        val service = UploadService(encryptor, outbox, writer, seeder)
        return OutboxBackupUploader(fileOps, source, AssetHasher(HashedExports(source, fileOps), fileOps), BackupRequestBuilder(fileOps), outbox, service, this)
    }

    private suspend fun queuedRequest(uniqueId: Uuid): Pair<Outbox, UploadFileRequest> {
        val row = assertNotNull(db.outbox.selectByDriveAndUnique(PhotobaseProtocol.DriveAlias, uniqueId))
        return row to OutboxSerializer.decode(row)
    }

    @Test
    fun anImageIsQueuedAsBulkOriginalBytesWithItsHashAndNoLocalRow() = runTest {
        // Metadata the chat path would scrub: a JPEG APP1 segment around GPS-ish text.
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) + "Exif GPS".encodeToByteArray()
        val path = file("photo.jpg", bytes)
        val (row, uniqueId) = rowFor("a", BackupMediaType.Image, bytes)
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, path, "image/jpeg", false)))

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        val (outboxRow, request) = queuedRequest(uniqueId)
        assertEquals(OUTBOX_BULK_PRIORITY, outboxRow.priority)
        assertTrue(request.existingFileIsSuccess)
        assertEquals(PhotobaseProtocol.PhotoFileType, request.metadata.appData.fileType)
        assertEquals(PhotobaseProtocol.ImageDataType, request.metadata.appData.dataType)
        assertEquals(12345, request.metadata.appData.userDate)

        val payload = request.payloads.single()
        assertEquals(PhotobaseProtocol.ImagePayloadKey, payload.key)
        assertEquals("image/jpeg", payload.contentType)
        val iv = assertNotNull(payload.iv)
        val payloadKey = KeyHeader(iv, request.keyHeader.aesKey)
        assertContentEquals(digest(bytes), payload.hash!!.plaintextContentHash(payloadKey))
        assertContentEquals(bytes, payloadKey.decrypt(File(payload.filePath).readBytes()))

        assertEquals(
            DescriptorContentV1(kind = DescriptorContentV1.Kind.IMAGE, name = "IMG_0001.HEIC", original = 1, quality = DescriptorContentV1.Quality.ORG),
            request.opened(payload),
            "sealed: the Photos drive never shows a name to the server",
        )

        assertEquals(0, writer.writes)
        File(payload.filePath).delete()
    }

    @Test
    fun bytesThatChangedAfterHashingQueueNothingAndLeaveNoTemp() = runTest {
        val (row, uniqueId) = rowFor("a", BackupMediaType.Image, "before".encodeToByteArray())
        val path = file("photo.jpg", "after".encodeToByteArray())
        val stagedBefore = stagedFiles()
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, path, "image/jpeg", false)))

        assertEquals(HandOffResult.ChangedUnderUs, uploader.handOff(row, uniqueId))

        assertNull(db.outbox.selectByDriveAndUnique(PhotobaseProtocol.DriveAlias, uniqueId))
        assertEquals(stagedBefore, stagedFiles())
    }

    @Test
    fun aRealPhotoKeepsItsBytesAndGetsItsSizeTinyAndEncryptedThumbnails() = runTest {
        val bytes = jpeg(1600, 1200)
        val (row, uniqueId) = rowFor("a", BackupMediaType.Image, bytes)
        // The library gave no type: it is still a picture, and its own bytes name the type.
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, file("IMG_7", bytes), "", false)))

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        val (_, request) = queuedRequest(uniqueId)
        val payload = request.payloads.single()
        val payloadKey = KeyHeader(payload.iv!!, request.keyHeader.aesKey)
        assertContentEquals(bytes, payloadKey.decrypt(File(payload.filePath).readBytes()))
        assertEquals("image/jpeg", payload.contentType)
        val tiny = assertNotNull(payload.previewThumbnail, "the tiny is plain on the payload, as in every app")
        assertEquals(tiny, request.metadata.appData.previewThumbnail)

        val descriptor = assertNotNull(request.opened(payload))
        assertEquals(1600 to 1200, descriptor.pixelWidth to descriptor.pixelHeight)
        assertEquals(1, descriptor.original)
        assertEquals("IMG_0001.HEIC", descriptor.name)

        assertTrue(request.thumbnails.isNotEmpty())
        request.thumbnails.forEach { thumb ->
            assertEquals(payload.key, thumb.key)
            assertEquals("image/webp", contentTypeFromBytes(payloadKey.decrypt(thumb.thumbnailBytes)), "encrypted with the payload's own IV")
        }

        val content = request.keyHeader.decrypt(Base64.decode(request.metadata.appData.content!!)).decodeToString()
        assertFalse("name" in content || "width" in content || "tiny" in content, content)
        File(payload.filePath).delete()
    }

    @Test
    fun aPhotosOwnCaptureTimeBecomesItsDate() = runTest {
        val bytes = File("../homebase-api/src/jvmTest/resources/test_images/canon_5d_srgb.jpg").readBytes()
        val (row, uniqueId) = rowFor("a", BackupMediaType.Image, bytes)
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, file("canon.jpg", bytes), "image/jpeg", false)))

        uploader.handOff(row, uniqueId)

        val (_, request) = queuedRequest(uniqueId)
        val taken = LocalDateTime(2018, 7, 2, 11, 33, 55).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        assertEquals(taken, request.metadata.appData.userDate, "from the photo's EXIF, not the library row's 12345")
        request.payloads.forEach { File(it.filePath).delete() }
    }

    @Test
    fun aVideoTheLibraryGaveNoTypeIsStillRecordedAsAVideo() = runTest {
        val still = "still".encodeToByteArray()
        val motion = "motion".encodeToByteArray()
        val (row, uniqueId) = rowFor("a", BackupMediaType.LivePhoto, still, motion)
        val uploader = backgroundScope.uploader(
            listOf(
                ExportedPart(ExportedPartRole.Primary, file("s.heic", still), "image/heic", false),
                ExportedPart(ExportedPartRole.PairedVideo, file("m.mov", motion), "application/octet-stream", false),
            ),
        )

        uploader.handOff(row, uniqueId)

        val (_, request) = queuedRequest(uniqueId)
        assertEquals(DescriptorContentV1(kind = DescriptorContentV1.Kind.VIDEO, original = 1), request.opened(request.payloads[1]))
        request.payloads.forEach { File(it.filePath).delete() }
    }

    @Test
    fun aPhotosOwnBytesDecideItsType() = runTest {
        val bytes = jpeg(64, 48)
        val (row, uniqueId) = rowFor("a", BackupMediaType.Image, bytes)
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, file("odd", bytes), "image/x-library-says", false)))

        uploader.handOff(row, uniqueId)

        val payload = queuedRequest(uniqueId).second.payloads.single()
        assertEquals("image/jpeg", payload.contentType, "the bytes decide, now that the detector's signatures are exact")
        File(payload.filePath).delete()
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, (x * 31 + y * 17) and 0xFFFFFF)
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    // Queued readable, with the provider asked to seal it on the way out.
    private fun UploadFileRequest.opened(payload: PayloadFile): DescriptorContentV1? {
        assertTrue(sealDescriptorContent, "Photos writes the current format ahead of the switch, so it asks for the seal")
        return DescriptorContentV1.parseOrNull(assertNotNull(payload.descriptorContent))
    }

    private fun stagedFiles(): Set<String> =
        File(fileOps.getOutboxStagingDirectory()).list()?.toSet().orEmpty()

    @Test
    fun aLivePhotoIsOneFileWithTwoPayloads() = runTest {
        val still = "still".encodeToByteArray()
        val motion = "motion".encodeToByteArray()
        val (row, uniqueId) = rowFor("a", BackupMediaType.LivePhoto, still, motion)
        val uploader = backgroundScope.uploader(
            listOf(
                ExportedPart(ExportedPartRole.Primary, file("s.heic", still), "image/heic", false),
                ExportedPart(ExportedPartRole.PairedVideo, file("m.mov", motion), "video/quicktime", false),
            ),
        )

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        val (_, request) = queuedRequest(uniqueId)
        assertEquals(PhotobaseProtocol.LivePhotoDataType, request.metadata.appData.dataType)
        assertEquals(listOf(PhotobaseProtocol.ImagePayloadKey, PhotobaseProtocol.LivePhotoVideoPayloadKey), request.payloads.map { it.key })
        assertNotEquals(request.payloads[0].iv!!.toList(), request.payloads[1].iv!!.toList())
        assertContentEquals(digest(motion), request.payloads[1].hash!!.plaintextContentHash(KeyHeader(request.payloads[1].iv!!, request.keyHeader.aesKey)))
        assertEquals(DescriptorContentV1(kind = DescriptorContentV1.Kind.VIDEO, original = 1), request.opened(request.payloads[1]), "the paired video is the user's file, with no name of its own")
        request.payloads.forEach { File(it.filePath).delete() }
    }

    @Test
    fun aVideoGoesThroughTheSharedPipelineWithNoLocalRow() = runTest {
        val bytes = "video bytes".encodeToByteArray()
        val (row, uniqueId) = rowFor("a", BackupMediaType.Video, bytes)
        val encryptor = PreviewingEncryptor()
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, file("v.mp4", bytes), "video/mp4", false)), encryptor)

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        val (outboxRow, request) = queuedRequest(uniqueId)
        assertEquals(1, encryptor.calls)
        assertEquals(OUTBOX_BULK_PRIORITY, outboxRow.priority)
        assertEquals(PhotobaseProtocol.VideoDataType, request.metadata.appData.dataType)
        assertNotNull(request.payloads.single().previewThumbnail, "the tiny video processing makes stays on the payload")
        assertTrue(request.existingFileIsSuccess)
        assertEquals(0, writer.writes)
        assertEquals(DescriptorContentV1(kind = DescriptorContentV1.Kind.VIDEO, name = "IMG_0001.HEIC"), request.opened(request.payloads.single()))
    }

    @Test
    fun aVideoThatChangedIsNotEncoded() = runTest {
        val (row, uniqueId) = rowFor("a", BackupMediaType.Video, "before".encodeToByteArray())
        val encryptor = PreviewingEncryptor()
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, file("v.mp4", "after".encodeToByteArray()), "video/mp4", false)), encryptor)

        assertEquals(HandOffResult.ChangedUnderUs, uploader.handOff(row, uniqueId))

        assertEquals(0, encryptor.calls)
    }

    @Test
    fun aRowOnlyCountsAsSendingWhileTheOutboxHoldsIt() = runTest {
        val bytes = "x".encodeToByteArray()
        val (row, uniqueId) = rowFor("a", BackupMediaType.Image, bytes)
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, file("p", bytes), "image/jpeg", false)))

        assertFalse(uniqueId in uploader.stillSending(mapOf(row to uniqueId)))
        uploader.handOff(row, uniqueId)
        assertTrue(uniqueId in uploader.stillSending(mapOf(row to uniqueId)))
    }
}

private object NeverSends : OutboxUploader {
    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) = error("the Outbox is offline in these tests")
}

private object NoEncryption : PayloadBundleEncryptor {
    override suspend fun encryptBundle(uniqueId: Uuid, bundle: PayloadBundle?, aesKey: SecureByteArray, scope: CoroutineScope): PayloadBundle =
        error("images don't go through encryptBundle")
}

private class PreviewingEncryptor : PayloadBundleEncryptor {
    var calls = 0

    override suspend fun encryptBundle(uniqueId: Uuid, bundle: PayloadBundle?, aesKey: SecureByteArray, scope: CoroutineScope): PayloadBundle {
        calls++
        // On the payload and nowhere else, which is where the real video processing puts it.
        val preview = id.homebase.api.client.drives.upload.EmbeddedThumb(1, 1, "image/webp", id.homebase.api.serialization.Base64Bytes(byteArrayOf(9, 9)))
        return bundle!!.copy(payloads = bundle.payloads.map { it.copy(previewThumbnail = preview, isPreEncrypted = true, iv = ByteArray(16)) })
    }
}

private class CountingWriter : OptimisticLocalWriter {
    var writes = 0

    override suspend fun writeNewFile(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        originalRecipientCount: Int,
        fileSystemType: FileSystemType,
        payloadDescriptors: List<PayloadDescriptor>?,
        fileId: Uuid,
    ): Uuid {
        writes++
        return fileId
    }

    override suspend fun writeUpdate(driveId: Uuid, keyHeader: KeyHeader, unecryptedMetadata: UploadFileMetadata, payloadDescriptors: List<PayloadDescriptor>?) {
        writes++
    }

    override suspend fun removeOptimisticFile(driveId: Uuid, uniqueId: Uuid) = false
}
