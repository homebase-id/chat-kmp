package id.homebase.photobase.backup

import id.homebase.upload.ContentHashes
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.Blake3Hasher
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.file.SourceUnavailableException
import id.homebase.api.file.contentTypeFromBytes
import id.homebase.api.file.uploadTempDirectory
import id.homebase.api.image.ImageMetadataScrubber
import id.homebase.api.image.MediaQuality
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.serialization.OutboxSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OUTBOX_BULK_PRIORITY
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.api.video.FFmpegBinaryManager
import id.homebase.api.video.SegmentedVideo
import id.homebase.api.video.VideoCompressionFailedException
import id.homebase.api.video.VideoCompressor
import id.homebase.api.video.VideoProber
import id.homebase.api.video.VideoProgressListener
import id.homebase.api.video.VideoTrackInfo
import id.homebase.api.video.VideoPayloadProcessor
import id.homebase.api.video.VideoQuality
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.upload.EMPTY_BUNDLE
import id.homebase.upload.OptimisticLocalWriter
import id.homebase.upload.PayloadBundle
import id.homebase.upload.PayloadBundleEncryptionService
import id.homebase.upload.PayloadBundleEncryptor
import id.homebase.upload.PayloadCacheSeeder
import id.homebase.upload.UploadService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assume.assumeTrue

class OutboxBackupUploaderTest {
    private val jvmFileOps = JvmFileOperationsProvider()
    private val streamedReads = mutableMapOf<String, Int>()
    private val fileOps: FileOperationsProvider = object : FileOperationsProvider by jvmFileOps {
        override fun readFileAsFlow(path: String, chunkSize: Int): Flow<ByteArray> {
            streamedReads[path] = (streamedReads[path] ?: 0) + 1
            return jvmFileOps.readFileAsFlow(path, chunkSize)
        }
    }
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
    private suspend fun rowFor(id: String, mediaType: BackupMediaType, vararg parts: ByteArray, failedBefore: Int = 0): Pair<id.homebase.api.sync.database.DeviceBackupAsset, Uuid> {
        val ledger = BackupLedger(db.deviceBackup) { 1000 }
        ledger.apply(fullListing(asset(id, createdAtMs = 12345, mediaType = mediaType)), null)
        val hashed = HashedAsset(parts.map { digest(it) })
        ledger.markHashed(id, hashed.contentHash, hashed.uniqueId, 10)
        repeat(failedBefore) { ledger.recordFailure(ledger.row(id)!!, "before") }
        return ledger.row(id)!! to hashed.uniqueId
    }

    private class PartsSource(val parts: List<ExportedPart>, val hashedCopy: Boolean = false) : DeviceChangeSource by FakeSource() {
        override suspend fun export(assetId: String, mediaType: BackupMediaType) = ExportedAsset(parts, "IMG_0001.HEIC", isHashedCopy = hashedCopy)
    }

    private fun CoroutineScope.uploader(
        parts: List<ExportedPart>,
        encryptor: PayloadBundleEncryptor = realEncryption(),
        photoQuality: MediaQuality = MediaQuality.ORIGINAL,
        videoQuality: MediaQuality = MediaQuality.HD,
        hashedCopy: Boolean = false,
    ): OutboxBackupUploader {
        val source = PartsSource(parts, hashedCopy)
        val outbox = OutboxSync(db, NeverSends, EventBus(), this).also { it.setOnline(false) }
        val http = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        val creds = CredentialsManager()
        val seeder = PayloadCacheSeeder(DriveFileProvider(http, creds, DriveFileProviderCached(http, creds, fileOps)), fileOps)
        val service = UploadService(encryptor, outbox, writer, seeder)
        val hasher = AssetHasher(HashedExports(source, fileOps), fileOps)
        val requests = BackupRequestBuilder(fileOps, hasher, settings = { BackupSettings(photoQuality = photoQuality, videoQuality = videoQuality) })
        return OutboxBackupUploader(fileOps, source, hasher, requests, outbox, service, this)
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
        assertEquals(
            DescriptorContentV1(kind = DescriptorContentV1.Kind.VIDEO, original = 1, video = DescriptorContentV1.Video(playableCopyKey = "vcp_0001")),
            request.opened(request.payloads[1]),
        )
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

    // A camera-like JPEG: an EXIF block whose only IFD0 entry is a text tag carrying a marker.
    private fun geotaggedJpeg(width: Int, height: Int): ByteArray {
        val plain = jpeg(width, height)
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun le32(v: Int) = ByteArray(4) { (v ushr (8 * it)).toByte() }
        val marker = "GPS-7749".encodeToByteArray()
        val tiff = "II".encodeToByteArray() + le16(42) + le32(8) + le16(1) + le16(0x010F) + le16(2) + le32(marker.size) + le32(26) + le32(0) + marker
        return withApp1(plain, "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff)
    }

    @Test
    fun aPhotoAtStandardOrHdKeepsItsExifInTheFinalImageAndNeverInAThumbnail() = runTest {
        for ((n, quality) in listOf(MediaQuality.STANDARD, MediaQuality.HD).withIndex()) {
            // Bytes of their own: the file's id comes from its hash.
            val bytes = geotaggedJpeg(5000 + n, 3000)
            val (row, uniqueId) = rowFor("q$n", BackupMediaType.Image, bytes)
            val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, file("IMG_$n.jpg", bytes), "image/jpeg", false)), photoQuality = quality)

            assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

            val request = queuedRequest(uniqueId).second
            val payload = request.payloads.single()
            val key = KeyHeader(payload.iv!!, request.keyHeader.aesKey)
            val sent = key.decrypt(File(payload.filePath).readBytes())
            assertEquals("image/webp", payload.contentType, "$quality is a re-encode")
            assertTrue(sent.size < bytes.size)
            val exif = assertNotNull(ImageMetadataScrubber.readBlocks(sent)?.exif, "$quality: Photos keeps metadata in the final image")
            assertTrue("GPS-7749" in exif.decodeToString())
            assertTrue(request.thumbnails.isNotEmpty())
            for (thumb in request.thumbnails) {
                assertNull(ImageMetadataScrubber.readBlocks(key.decrypt(thumb.thumbnailBytes)), "$quality: a ${thumb.pixelWidth}px thumbnail never carries metadata")
            }
            val descriptor = assertNotNull(request.opened(payload))
            assertEquals(0, descriptor.original)
            assertEquals(DescriptorContentV1.Quality.of(quality), descriptor.quality)
            File(payload.filePath).delete()
        }
    }

    private val filmedAt = "+37.7749-122.4194/"

    private fun ffmpeg(vararg args: String): String {
        val process = FFmpegBinaryManager.processBuilder(*args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { out }
        return out
    }

    // The 26 KB test clip, tagged as a camera would with where it was filmed; [take] makes its bytes, and so its id, its own.
    private fun geotaggedClip(take: Int = 0): String {
        val sample = file("sample.mp4", this::class.java.getResourceAsStream("/test_videos/sample.mp4")!!.readBytes())
        val tagged = File(workDir, "tagged$take.mov").path
        ffmpeg(FFmpegBinaryManager.ffmpegPath(), "-y", "-i", sample, "-c", "copy", "-metadata", "location=$filmedAt", "-metadata", "title=take $take", tagged)
        return tagged
    }

    private fun locationOf(video: ByteArray): String? {
        val path = file("probe_${video.size}.mp4", video)
        return ffmpeg(FFmpegBinaryManager.ffprobePath(), "-v", "error", "-show_entries", "format_tags=location", "-of", "default=noprint_wrappers=1:nokey=1", path)
            .trim().ifEmpty { null }
    }

    private fun realEncryption() = PayloadBundleEncryptionService(fileOps, VideoPayloadProcessor(fileOps), EventBus())

    @Test
    fun aVideoAtStandardOrHdIsATranscodeThatKeepsWhereItWasFilmed() = runTest {
        assumeTrue("FFmpeg binaries not bundled in this test classpath", FFmpegBinaryManager.isAvailable())
        for ((n, quality) in listOf(MediaQuality.STANDARD, MediaQuality.HD).withIndex()) {
            val clip = geotaggedClip(n)
            val bytes = File(clip).readBytes()
            assertEquals(filmedAt, locationOf(bytes), "the fixture carries a location")
            val (row, uniqueId) = rowFor("v$n", BackupMediaType.Video, bytes)
            val uploader = backgroundScope.uploader(
                listOf(ExportedPart(ExportedPartRole.Primary, clip, "video/quicktime", false)),
                videoQuality = quality,
            )

            assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

            val request = queuedRequest(uniqueId).second
            val payload = request.payloads.single { it.key == PhotobaseProtocol.VideoPayloadKey }
            val sent = KeyHeader(payload.iv!!, request.keyHeader.aesKey).decrypt(File(payload.filePath).readBytes())
            assertFalse(sent.contentEquals(bytes), "$quality is a transcode")
            assertEquals(filmedAt, locationOf(sent), "$quality: Photos keeps where it was filmed")
            assertEquals(PhotoContent(latitude = 37.7749, longitude = -122.4194), request.photoContent(), "$quality: and in the file's own content, which an HLS stream can't lose")
        }
    }

    @Test
    fun aVideoAtOriginalKeepsWhereItWasFilmedInTheFileAndItsCopy() = runTest {
        assumeTrue("FFmpeg binaries not bundled in this test classpath", FFmpegBinaryManager.isAvailable())
        val clip = geotaggedClip()
        val bytes = File(clip).readBytes()
        val (row, uniqueId) = rowFor("a", BackupMediaType.Video, bytes)
        val uploader = backgroundScope.uploader(
            listOf(ExportedPart(ExportedPartRole.Primary, clip, "video/quicktime", false)),
            videoQuality = MediaQuality.ORIGINAL,
        )

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        val request = queuedRequest(uniqueId).second
        suspend fun sent(key: String) = request.payloads.single { it.key == key }.let { KeyHeader(it.iv!!, request.keyHeader.aesKey).decrypt(File(it.filePath).readBytes()) }
        assertContentEquals(bytes, sent(PhotobaseProtocol.VideoPayloadKey), "the user's file, untouched")
        val copy = sent("vcp_0000")
        assertFalse(copy.contentEquals(bytes), "the copy is a transcode")
        assertEquals(filmedAt, locationOf(copy), "and keeps where it was filmed")
        assertNotNull(request.payloads.single { it.key == PhotobaseProtocol.VideoPayloadKey }.hash, "the original is hashed as it's encrypted")
        assertEquals(PhotoContent(latitude = 37.7749, longitude = -122.4194), request.photoContent())
        assertEquals(1, streamedReads[clip], "the hand-off streams the original once, as it's encrypted and checked; its location comes from the moov alone")
    }

    @Test
    fun aTranscodeOfAHashedCopyFindsItsLocationWithoutReadingTheCopyAgain() = runTest {
        assumeTrue("FFmpeg binaries not bundled in this test classpath", FFmpegBinaryManager.isAvailable())
        val clip = geotaggedClip()
        val (row, uniqueId) = rowFor("a", BackupMediaType.Video, File(clip).readBytes())
        val uploader = backgroundScope.uploader(
            listOf(ExportedPart(ExportedPartRole.Primary, clip, "video/quicktime", false)),
            videoQuality = MediaQuality.STANDARD,
            hashedCopy = true,
        )

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        assertEquals(PhotoContent(latitude = 37.7749, longitude = -122.4194), queuedRequest(uniqueId).second.photoContent())
        assertNull(streamedReads[clip], "the copy is those very bytes, and the transcode reads it on its own")
    }

    @Test
    fun anOriginalVideoThatChangedBeforeItsEncryptedIsNotUploaded() = runTest {
        // A hashed copy skips the up-front check, so only the check as it's encrypted can catch this.
        val (row, uniqueId) = rowFor("a", BackupMediaType.Video, "what was hashed".encodeToByteArray())
        val stagedBefore = stagedFiles()
        val uploader = backgroundScope.uploader(
            listOf(ExportedPart(ExportedPartRole.Primary, file("v.mov", "what is there now".encodeToByteArray()), "video/quicktime", false)),
            videoQuality = MediaQuality.ORIGINAL,
            hashedCopy = true,
        )

        assertEquals(HandOffResult.ChangedUnderUs, uploader.handOff(row, uniqueId))
        assertNull(db.outbox.selectByDriveAndUnique(PhotobaseProtocol.DriveAlias, uniqueId))
        assertEquals(stagedBefore, stagedFiles())
    }

    @Test
    fun anOriginalVideoWithNoPlayableCopyIsBackedUpAlone() = runTest {
        assumeTrue("FFmpeg binaries not bundled in this test classpath", FFmpegBinaryManager.isAvailable())
        // Bytes no ffmpeg can transcode.
        val bytes = ByteArray(4096) { (it * 31).toByte() }
        val (row, uniqueId) = rowFor("a", BackupMediaType.Video, bytes)
        val uploader = backgroundScope.uploader(
            listOf(ExportedPart(ExportedPartRole.Primary, file("v.mov", bytes), "video/quicktime", false)),
            videoQuality = MediaQuality.ORIGINAL,
        )

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        val request = queuedRequest(uniqueId).second
        val original = request.payloads.single()
        assertEquals(PhotobaseProtocol.VideoPayloadKey, original.key)
        assertContentEquals(bytes, KeyHeader(original.iv!!, request.keyHeader.aesKey).decrypt(File(original.filePath).readBytes()))
        val described = assertNotNull(request.opened(original))
        assertEquals(1, described.original)
        assertNull(described.video?.playableCopyKey, "no copy to name: it plays as it is")
    }

    private suspend fun UploadFileRequest.photoContent(): PhotoContent =
        OdinSystemSerializer.deserialize(keyHeader.decrypt(Base64.decode(metadata.appData.content!!)).decodeToString())

    @Test
    fun aPhotoThatChangedIsNotUploaded() = runTest {
        val (row, uniqueId) = rowFor("a", BackupMediaType.Image, jpeg(400, 300))
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, file("p.jpg", jpeg(401, 300)), "image/jpeg", false)), photoQuality = MediaQuality.STANDARD)

        assertEquals(HandOffResult.ChangedUnderUs, uploader.handOff(row, uniqueId))
        assertNull(db.outbox.selectByDriveAndUnique(PhotobaseProtocol.DriveAlias, uniqueId))
    }

    @Test
    fun aVideoAtOriginalGoesUntouchedWithAPlayableCopy() = runTest {
        val bytes = "video bytes".encodeToByteArray()
        val (row, uniqueId) = rowFor("a", BackupMediaType.Video, bytes)
        val uploader = backgroundScope.uploader(
            listOf(ExportedPart(ExportedPartRole.Primary, file("v.mov", bytes), "video/quicktime", false)),
            PreviewingEncryptor(),
            videoQuality = MediaQuality.ORIGINAL,
        )

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        val (original, copy) = queuedRequest(uniqueId).second.payloads.also { assertEquals(2, it.size) }
        assertEquals(PhotobaseProtocol.VideoPayloadKey, original.key)
        assertEquals("vcp_0000", copy.key)
        val described = assertNotNull(DescriptorContentV1.parseOrNull(original.descriptorContent!!))
        assertEquals(1, described.original)
        assertEquals("vcp_0000", described.video?.playableCopyKey)
        assertEquals(VideoQuality.HIGH, copy.videoQuality, "the copy plays at 1080p")
        assertFalse(copy.stripMetadata, "and keeps the video's location")
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

    private fun livePhoto(still: ByteArray, motion: ByteArray) = listOf(
        ExportedPart(ExportedPartRole.Primary, file("s.jpg", still), "image/jpeg", false),
        ExportedPart(ExportedPartRole.PairedVideo, file("m.mov", motion), "video/quicktime", false),
    )

    private fun UploadFileRequest.payload(key: String) = payloads.single { it.key == key }

    @Test
    fun anIphoneLivePhotoAtOriginalKeepsItsVideoAndPlaysACopy() = runTest {
        val still = "still".encodeToByteArray()
        val motion = "motion".encodeToByteArray()
        val (row, uniqueId) = rowFor("a", BackupMediaType.LivePhoto, still, motion)
        val uploader = backgroundScope.uploader(livePhoto(still, motion), PreviewingEncryptor(), photoQuality = MediaQuality.ORIGINAL, videoQuality = MediaQuality.STANDARD)

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        val request = queuedRequest(uniqueId).second
        assertEquals(PhotobaseProtocol.LivePhotoDataType, request.metadata.appData.dataType)
        assertEquals(listOf(PhotobaseProtocol.ImagePayloadKey, PhotobaseProtocol.LivePhotoVideoPayloadKey, "vcp_0001"), request.payloads.map { it.key })
        val live = request.payload(PhotobaseProtocol.LivePhotoVideoPayloadKey)
        val described = assertNotNull(request.opened(live))
        assertEquals(1, described.original, "the paired video is kept as it is")
        assertEquals("vcp_0001", described.video?.playableCopyKey)
        val copy = request.payload("vcp_0001")
        assertEquals(VideoQuality.HIGH, copy.videoQuality, "the copy plays at 1080p, whatever the video setting")
        assertFalse(copy.stripMetadata)
        assertFalse(live.poster || copy.poster, "the still's thumbnails stand for the clip")
    }

    @Test
    fun anIphoneLivePhotoAtStandardOrHdSendsItsVideoAsATranscodeAtThePhotoSetting() = runTest {
        for ((n, quality) in listOf(MediaQuality.STANDARD to VideoQuality.STANDARD, MediaQuality.HD to VideoQuality.HIGH).withIndex()) {
            val still = "still $n".encodeToByteArray()
            val motion = "motion $n".encodeToByteArray()
            val (row, uniqueId) = rowFor("a$n", BackupMediaType.LivePhoto, still, motion)
            val uploader = backgroundScope.uploader(livePhoto(still, motion), PreviewingEncryptor(), photoQuality = quality.first, videoQuality = MediaQuality.ORIGINAL)

            assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

            val request = queuedRequest(uniqueId).second
            assertEquals(PhotobaseProtocol.LivePhotoDataType, request.metadata.appData.dataType)
            assertEquals(listOf(PhotobaseProtocol.ImagePayloadKey, PhotobaseProtocol.LivePhotoVideoPayloadKey), request.payloads.map { it.key })
            val live = request.payload(PhotobaseProtocol.LivePhotoVideoPayloadKey)
            assertFalse(live.skipVideoProcessing, "${quality.first}: a transcode")
            assertEquals(quality.second, live.videoQuality, "${quality.first}: the clip follows the photo setting")
            assertFalse(live.poster)
            assertNull(request.opened(live)?.original)
        }
    }

    @Test
    fun anIphoneLivePhotoWhoseVideoChangedIsNotUploaded() = runTest {
        val still = "still".encodeToByteArray()
        val (row, uniqueId) = rowFor("a", BackupMediaType.LivePhoto, still, "motion".encodeToByteArray())
        val uploader = backgroundScope.uploader(livePhoto(still, "edited".encodeToByteArray()), PreviewingEncryptor(), photoQuality = MediaQuality.STANDARD)

        assertEquals(HandOffResult.ChangedUnderUs, uploader.handOff(row, uniqueId))
        assertNull(db.outbox.selectByDriveAndUnique(PhotobaseProtocol.DriveAlias, uniqueId))
    }

    @Test
    fun anIphoneLivePhotoWhoseVideoCantBeCopiedKeepsItAtOriginalAndIsAStillOnItsLastAttemptOtherwise() = runTest {
        assumeTrue("FFmpeg binaries not bundled in this test classpath", FFmpegBinaryManager.isAvailable())
        for ((n, quality) in listOf(MediaQuality.ORIGINAL, MediaQuality.STANDARD).withIndex()) {
            val still = "still $n".encodeToByteArray()
            // Bytes no ffmpeg can transcode.
            val motion = ByteArray(4096) { (it * 31 + n).toByte() }
            val lastAttempt = if (quality == MediaQuality.ORIGINAL) 0 else BackupLedger.MAX_ATTEMPTS - 1
            val (row, uniqueId) = rowFor("a$n", BackupMediaType.LivePhoto, still, motion, failedBefore = lastAttempt)
            val uploader = backgroundScope.uploader(livePhoto(still, motion), photoQuality = quality)

            assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

            val request = queuedRequest(uniqueId).second
            if (quality == MediaQuality.ORIGINAL) {
                assertEquals(PhotobaseProtocol.LivePhotoDataType, request.metadata.appData.dataType)
                assertEquals(listOf(PhotobaseProtocol.ImagePayloadKey, PhotobaseProtocol.LivePhotoVideoPayloadKey), request.payloads.map { it.key })
                val live = request.payload(PhotobaseProtocol.LivePhotoVideoPayloadKey)
                assertNotEquals(request.payload(PhotobaseProtocol.ImagePayloadKey).iv!!.toList(), live.iv!!.toList(), "each payload has its own IV")
                assertContentEquals(digest(motion), live.hash!!.plaintextContentHash(KeyHeader(live.iv!!, request.keyHeader.aesKey)), "the user's file, checked as it's encrypted")
                assertEquals(DescriptorContentV1(kind = DescriptorContentV1.Kind.VIDEO, original = 1), request.opened(live), "no copy to name: it plays as it is")
            } else {
                assertEquals(PhotobaseProtocol.ImageDataType, request.metadata.appData.dataType, "$quality: a still")
                assertEquals(listOf(PhotobaseProtocol.ImagePayloadKey), request.payloads.map { it.key })
            }
            request.payloads.forEach { File(it.filePath).delete() }
        }
    }

    @Test
    fun anIphoneLivePhotoAtOriginalIsHashedAsItsEncryptedWithAnIvPerPayload() = runTest {
        val still = "still".encodeToByteArray()
        val motion = "motion".encodeToByteArray()
        val (row, uniqueId) = rowFor("a", BackupMediaType.LivePhoto, still, motion)
        val encryption = PayloadBundleEncryptionService(fileOps, VideoPayloadProcessor(fileOps, CopyingCompressor, NoProbe), EventBus())
        val uploader = backgroundScope.uploader(livePhoto(still, motion), encryption, photoQuality = MediaQuality.ORIGINAL)

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        val request = queuedRequest(uniqueId).second
        assertEquals(listOf(PhotobaseProtocol.ImagePayloadKey, PhotobaseProtocol.LivePhotoVideoPayloadKey, "vcp_0001"), request.payloads.map { it.key })
        val ivs = request.payloads.map { it.iv!!.toList() }
        assertEquals(ivs.size, ivs.toSet().size, "each payload has its own IV")
        val live = request.payload(PhotobaseProtocol.LivePhotoVideoPayloadKey)
        assertContentEquals(digest(motion), live.hash!!.plaintextContentHash(KeyHeader(live.iv!!, request.keyHeader.aesKey)), "the paired video, checked as it's encrypted")
        assertContentEquals(digest(still), request.payload(PhotobaseProtocol.ImagePayloadKey).let { it.hash!!.plaintextContentHash(KeyHeader(it.iv!!, request.keyHeader.aesKey)) })
        request.payloads.forEach { File(it.filePath).delete() }
    }

    private fun motionPhotoPart(clip: ByteArray, name: String = "PXL.MP.jpg") =
        ExportedPart(ExportedPartRole.Primary, file(name, motionPhoto(jpeg(400, 300), clip)), "image/jpeg", false)

    private fun uploadTemps(): List<String> = File(fileOps.uploadTempDirectory()).list()?.filter { it.startsWith("motion") }.orEmpty()

    @Test
    fun anAndroidMotionPhotoSendsItsClipAsATranscodeAndKeepsTheFileAtOriginal() = runTest {
        val tempsBefore = uploadTemps()
        val settings = listOf(MediaQuality.STANDARD to VideoQuality.STANDARD, MediaQuality.HD to VideoQuality.HIGH, MediaQuality.ORIGINAL to VideoQuality.HIGH)
        for ((n, quality) in settings.withIndex()) {
            val part = motionPhotoPart(fakeMp4(5000 + n), "PXL$n.MP.jpg")
            val bytes = File(part.path).readBytes()
            val (row, uniqueId) = rowFor("a$n", BackupMediaType.Image, bytes)
            val uploader = backgroundScope.uploader(listOf(part), PreviewingEncryptor(), photoQuality = quality.first, videoQuality = MediaQuality.ORIGINAL)

            assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

            val request = queuedRequest(uniqueId).second
            assertEquals(PhotobaseProtocol.LivePhotoDataType, request.metadata.appData.dataType, "${quality.first}: one format on both phones")
            assertEquals(listOf(PhotobaseProtocol.ImagePayloadKey, PhotobaseProtocol.LivePhotoVideoPayloadKey), request.payloads.map { it.key }, "${quality.first}: no second copy of the clip")
            val still = request.payload(PhotobaseProtocol.ImagePayloadKey)
            assertEquals(quality.first == MediaQuality.ORIGINAL, still.filePath == part.path, "${quality.first}: the file is kept as it is only at Original")
            val live = request.payload(PhotobaseProtocol.LivePhotoVideoPayloadKey)
            assertEquals("video/mp4", live.contentType)
            assertFalse(live.skipVideoProcessing, "${quality.first}: the clip is a transcode")
            assertEquals(quality.second, live.videoQuality)
            assertFalse(live.poster)
            assertNull(request.opened(live)?.original)
        }
        assertEquals(tempsBefore, uploadTemps(), "the cut clips are gone once queued")
    }

    @Test
    fun aPlainPhotoIsAStill() = runTest {
        val bytes = jpeg(400, 300)
        val (row, uniqueId) = rowFor("a", BackupMediaType.Image, bytes)
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, file("p.jpg", bytes), "image/jpeg", false)), PreviewingEncryptor())

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        val request = queuedRequest(uniqueId).second
        assertEquals(PhotobaseProtocol.ImageDataType, request.metadata.appData.dataType)
        assertEquals(listOf(PhotobaseProtocol.ImagePayloadKey), request.payloads.map { it.key })
    }

    @Test
    fun aMotionPhotoWhoseClipFailsToTranscodeIsRetriedAndOnlyItsLastAttemptGoesAsAStill() = runTest {
        assumeTrue("FFmpeg binaries not bundled in this test classpath", FFmpegBinaryManager.isAvailable())
        val part = motionPhotoPart(fakeMp4(5000))
        val bytes = File(part.path).readBytes()
        val tempsBefore = uploadTemps()
        val (early, earlyId) = rowFor("early", BackupMediaType.Image, bytes, failedBefore = BackupLedger.MAX_ATTEMPTS - 2)
        assertFailsWith<VideoCompressionFailedException>("a failure the run backs off and retries") {
            backgroundScope.uploader(listOf(part), photoQuality = MediaQuality.ORIGINAL).handOff(early, earlyId)
        }
        assertNull(db.outbox.selectByDriveAndUnique(PhotobaseProtocol.DriveAlias, earlyId))

        val (row, uniqueId) = rowFor("a", BackupMediaType.Image, bytes, failedBefore = BackupLedger.MAX_ATTEMPTS - 1)
        val uploader = backgroundScope.uploader(listOf(part), photoQuality = MediaQuality.ORIGINAL)

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

        val request = queuedRequest(uniqueId).second
        assertEquals(PhotobaseProtocol.ImageDataType, request.metadata.appData.dataType)
        val still = request.payloads.single()
        assertContentEquals(bytes, KeyHeader(still.iv!!, request.keyHeader.aesKey).decrypt(File(still.filePath).readBytes()), "the file, its clip still inside")
        assertEquals(tempsBefore, uploadTemps())
        File(still.filePath).delete()
    }

    @Test
    fun theClipOfALiveOrMotionPhotoIsATranscodeWithNoThumbnailsOfItsOwn() = runTest {
        assumeTrue("FFmpeg binaries not bundled in this test classpath", FFmpegBinaryManager.isAvailable())
        val clip = File(geotaggedClip()).readBytes()
        val iphone = jpeg(400, 300).let { still -> livePhoto(still, clip) to listOf(still, clip) }
        val android = motionPhotoPart(clip).let { listOf(it) to listOf(File(it.path).readBytes()) }
        for ((n, export) in listOf("iPhone" to iphone, "Android" to android).withIndex()) {
            val (phone, parts) = export
            val (row, uniqueId) = rowFor("a$n", if (n == 0) BackupMediaType.LivePhoto else BackupMediaType.Image, *parts.second.toTypedArray())
            val uploader = backgroundScope.uploader(parts.first, photoQuality = MediaQuality.HD)

            assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))

            val request = queuedRequest(uniqueId).second
            assertEquals(PhotobaseProtocol.LivePhotoDataType, request.metadata.appData.dataType, phone)
            val live = request.payload(PhotobaseProtocol.LivePhotoVideoPayloadKey)
            val sent = KeyHeader(live.iv!!, request.keyHeader.aesKey).decrypt(File(live.filePath).readBytes())
            assertFalse(sent.contentEquals(clip), "$phone: a transcode")
            assertEquals(filmedAt, locationOf(sent), "$phone: Photos keeps where the clip was filmed")
            assertNull(live.previewThumbnail, "$phone: no tiny")
            assertTrue(request.thumbnails.none { it.key == live.key }, "$phone: no thumbnails")
            assertTrue(request.thumbnails.any { it.key == PhotobaseProtocol.ImagePayloadKey }, "$phone: the still's stand for it")
            assertNotNull(request.metadata.appData.previewThumbnail, "$phone: the file's tiny is the still's")
            request.payloads.forEach { File(it.filePath).delete() }
        }
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
    fun aPhotoHandedOffTwiceIsQueuedOnceAndLeavesNoSecondCopy() = runTest {
        val bytes = jpeg(640, 480)
        val (row, uniqueId) = rowFor("a", BackupMediaType.Image, bytes)
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, file("p.jpg", bytes), "image/jpeg", false)))
        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId))
        val afterFirst = stagedFiles()

        assertEquals(HandOffResult.Queued, uploader.handOff(row, uniqueId), "already queued counts as handed off")

        assertEquals(afterFirst, stagedFiles(), "the second copy is deleted, the queued one kept")
        queuedRequest(uniqueId).second.payloads.forEach { File(it.filePath).delete() }
    }

    @Test
    fun aHandOffStoppedPartWayLeavesNoEncryptedCopy() = runTest {
        val still = "still".encodeToByteArray()
        val motion = "motion".encodeToByteArray()
        val (row, uniqueId) = rowFor("a", BackupMediaType.LivePhoto, still, motion)
        val stagedBefore = stagedFiles()
        val pairedVideo = file("m.mov", motion)
        // Stopped as the paired video is read, with the still already encrypted.
        val stoppedOnTheVideo = object : FileOperationsProvider by fileOps {
            override fun readFileAsFlow(path: String, chunkSize: Int): Flow<ByteArray> =
                if (path == pairedVideo) flow { throw CancellationException("the backup was stopped") } else fileOps.readFileAsFlow(path, chunkSize)
        }
        val uploader = backgroundScope.uploader(
            listOf(
                ExportedPart(ExportedPartRole.Primary, file("s.heic", still), "image/heic", false),
                ExportedPart(ExportedPartRole.PairedVideo, pairedVideo, "video/quicktime", false),
            ),
            PayloadBundleEncryptionService(stoppedOnTheVideo, VideoPayloadProcessor(stoppedOnTheVideo), EventBus()),
        )

        assertFailsWith<CancellationException> { uploader.handOff(row, uniqueId) }

        assertEquals(stagedBefore, stagedFiles())
    }

    @Test
    fun aStagedFileGoneBeforeTheQueueIsRetriedNotSkipped() = runTest {
        val bytes = jpeg(640, 480)
        val (row, uniqueId) = rowFor("a", BackupMediaType.Image, bytes)
        val stagedBefore = stagedFiles()
        val gone = object : PayloadBundleEncryptor by realEncryption() {
            override suspend fun encryptBundle(uniqueId: Uuid, bundle: PayloadBundle?, aesKey: SecureByteArray, scope: CoroutineScope, hashes: ContentHashes?): PayloadBundle =
                throw SourceUnavailableException(bundle!!.payloads.first().filePath)
        }
        val uploader = backgroundScope.uploader(listOf(ExportedPart(ExportedPartRole.Primary, file("p.jpg", bytes), "image/jpeg", false)), gone)

        assertFailsWith<BackupEnqueueFailedException> { uploader.handOff(row, uniqueId) }

        assertNull(db.outbox.selectByDriveAndUnique(PhotobaseProtocol.DriveAlias, uniqueId))
        assertEquals(stagedBefore, stagedFiles())
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

/** [jpeg] with an APP1 segment holding [payload] right after its SOI. */
private fun withApp1(jpeg: ByteArray, payload: ByteArray): ByteArray {
    val length = payload.size + 2
    val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
}

/** [jpeg] as a Pixel writes a motion photo: its XMP after the SOI declaring [clip], which is appended. */
private fun motionPhoto(jpeg: ByteArray, clip: ByteArray): ByteArray {
    val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
        """<rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" """ +
        """xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1"><Container:Directory><rdf:Seq>""" +
        """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/></rdf:li>""" +
        """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${clip.size}" Item:Padding="0"/></rdf:li>""" +
        """</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
    return withApp1(jpeg, "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray() + xml.encodeToByteArray()) + clip
}

private fun fakeMp4(size: Int) = ByteArray(size) { (it * 13).toByte() }.also {
    byteArrayOf(0, 0, 0, 24).copyInto(it)
    "ftypisom".encodeToByteArray().copyInto(it, 4)
}

/** A transcode that copies its input, so the encryption step runs with no ffmpeg. */
private object CopyingCompressor : VideoCompressor {
    override suspend fun compress(inputPath: String, onProgress: VideoProgressListener?, trimStartMs: Long?, trimEndMs: Long?, quality: VideoQuality, keepMetadata: Boolean): String =
        File.createTempFile("copy", ".mp4").also { File(inputPath).copyTo(it, overwrite = true) }.path

    override suspend fun segmentAndEncrypt(inputPath: String, keyHeader: KeyHeader, onProgress: VideoProgressListener?): SegmentedVideo? = null
}

private object NoProbe : VideoProber {
    override suspend fun getDurationMs(inputPath: String) = 0L
    override suspend fun getFfmpegVersion(): String? = null
    override suspend fun probeVideo(inputPath: String): VideoTrackInfo? = null
    override fun canRead(contentType: String) = true
}

private class PreviewingEncryptor : PayloadBundleEncryptor {
    override suspend fun stageBundle(bundle: PayloadBundle?) = bundle ?: EMPTY_BUNDLE
    override suspend fun discard(bundle: PayloadBundle) = Unit

    var calls = 0

    override suspend fun encryptBundle(uniqueId: Uuid, bundle: PayloadBundle?, aesKey: SecureByteArray, scope: CoroutineScope, hashes: ContentHashes?): PayloadBundle {
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
