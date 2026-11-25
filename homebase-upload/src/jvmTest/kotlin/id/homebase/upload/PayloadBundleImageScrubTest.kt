package id.homebase.upload

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.image.ImageMetadataScrubber
import id.homebase.api.media.MediaMetadataStripper
import kotlinx.coroutines.flow.Flow
import id.homebase.api.video.VideoPayloadProcessor
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The scrub must happen at the send boundary, not merely be available as a helper (#1297).
 * [ImageMetadataScrubberTest] covers the byte surgery itself; this pins that
 * [PayloadBundleEncryptionService] actually applies it to what goes on the wire.
 */
class PayloadBundleImageScrubTest {

    private fun service(fileOps: FileOperationsProvider) =
        PayloadBundleEncryptionService(
            fileOps = fileOps,
            videoProcessor = VideoPayloadProcessor(fileOps),
            eventBus = EventBus(),
        )

    private fun bundle(vararg payloads: PayloadFile) =
        PayloadBundle(payloads = payloads.toList(), thumbnails = emptyList(), previewThumbs = emptyList())

    private val gpsMarker = "7749".encodeToByteArray()
    private val scanData = ByteArray(96) { (it % 241).toByte() }

    /** A JPEG carrying an EXIF block with Orientation=6 and a GPS IFD. */
    private fun geotaggedJpeg(): ByteArray {
        fun seg(marker: Int, payload: ByteArray): ByteArray {
            val len = payload.size + 2
            return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) +
                payload
        }
        val exif = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x02, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00,
            0x25, 0x88.toByte(), 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x26, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x02, 0x00, 0x05, 0x00, 0x01, 0x00, 0x00, 0x00,
        ) + gpsMarker + byteArrayOf(0x00, 0x00, 0x00, 0x00)

        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            seg(0xE1, exif) +
            seg(0xDB, ByteArray(65) { 3 }) +
            seg(0xC0, byteArrayOf(8, 0, 16, 0, 16, 1, 0x11, 0)) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 1, 1, 0, 0, 0x3F, 0) +
            scanData +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    @Test
    fun encryptBundle_uploadsImageWithMetadataStripped() = runTest {
        val provider = JvmFileOperationsProvider()
        val svc = service(provider)
        val key = KeyHeader.newRandom16()

        val original = geotaggedJpeg()
        val src = provider.writeBytesToTempFile(original, "scrub_src_", ".jpg")
        var staged: String? = null
        try {
            val result = svc.encryptBundle(
                Uuid.random(),
                bundle(PayloadFile(key = "p0", filePath = src, contentType = "image/jpeg")),
                key.aesKey,
                this,
            )
            val payload = result.payloads.single()
            staged = payload.filePath
            val iv = assertNotNull(payload.iv)

            // What actually went to the wire is the scrubbed bytes, not the picked file.
            val expected = ImageMetadataScrubber.scrub(original)
            assertFalse(expected.contentEquals(original), "fixture must have had something to strip")
            assertContentEquals(
                KeyHeader(aesKey = key.aesKey, iv = iv).encryptDataAes(expected),
                File(staged).readBytes(),
                "uploaded ciphertext must be of the SCRUBBED bytes",
            )

            // And the scrubbed plaintext keeps the picture while losing the coordinates.
            assertFalse(indexOfSub(expected, gpsMarker) >= 0, "GPS bytes must not reach the wire")
            assertTrue(indexOfSub(expected, scanData) >= 0, "pixel data must be untouched")
        } finally {
            provider.deleteTempFile(src)
            staged?.let { provider.deleteTempFile(it) }
        }
    }

    @Test
    fun aPublicUploadStagesTheScrubbedCopyDurably() = runTest {
        val provider = JvmFileOperationsProvider()
        val original = geotaggedJpeg()
        val src = provider.writeBytesToTempFile(original, "public_src_", ".jpg")
        val public = service(provider).stageBundle(Uuid.random(), bundle(PayloadFile(key = "pst_mdi0", filePath = src, contentType = "image/jpeg")), this).payloads.single()
        try {
            val sent = File(public.filePath).readBytes()
            assertContentEquals(ImageMetadataScrubber.scrub(original), sent, "the same function as the encryption step")
            assertFalse(indexOfSub(sent, gpsMarker) >= 0, "GPS bytes must not be published")
            assertTrue(public.filePath.startsWith(provider.getOutboxStagingDirectory()), "the outbox sends it later, so it is staged durably")
            assertContentEquals(original, File(src).readBytes(), "the user's file is left alone")
        } finally {
            provider.deleteTempFile(src)
            provider.deleteTempFile(public.filePath)
        }
    }

    @Test
    fun aPublicUploadKeepsWhatItsAppKeepsAndLeavesOtherFilesAlone() = runTest {
        val provider = JvmFileOperationsProvider()
        val original = geotaggedJpeg()
        val src = provider.writeBytesToTempFile(original, "public_keep_", ".jpg")
        val staged = service(provider).stageBundle(
            Uuid.random(),
            bundle(
                PayloadFile(key = "p0", filePath = src, contentType = "image/jpeg", stripMetadata = false),
                PayloadFile(key = "p1", filePath = src, contentType = "application/pdf"),
            ),
            this,
        ).payloads
        try {
            staged.forEach { assertContentEquals(original, File(it.filePath).readBytes(), it.key) }
        } finally {
            provider.deleteTempFile(src)
            staged.forEach { provider.deleteTempFile(it.filePath) }
        }
    }

    /** Counts whole-file reads of [source] and every file written. */
    private class CountingFiles(private val files: FileOperationsProvider, private val source: String) : FileOperationsProvider by files {
        var reads = 0
        var writes = 0
        private fun read(path: String) { if (path == source) reads++ }
        override fun openFileInput(path: String) = files.openFileInput(path).also { read(path) }
        override suspend fun readFileBytes(path: String) = files.readFileBytes(path).also { read(path) }
        override fun readFileAsFlow(path: String, chunkSize: Int) = files.readFileAsFlow(path, chunkSize).also { read(path) }
        override suspend fun readFileHeaderBytes(path: String, maxBytes: Int) = files.readFileHeaderBytes(path, maxBytes).also { read(path) }
        override suspend fun readFileRange(path: String, offset: Long, length: Int) = files.readFileRange(path, offset, length).also { read(path) }
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = files.writeStream(path, data).also { writes++ }
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String) = files.writeBytesToTempFile(bytes, prefix, suffix).also { writes++ }
        override suspend fun writeBytesToOutboxTempFile(bytes: ByteArray, prefix: String, suffix: String) = files.writeBytesToOutboxTempFile(bytes, prefix, suffix).also { writes++ }
    }

    @Test
    fun everyPayloadIsReadOnceAndWrittenOnceWhetherOrNotItsStripped() = runTest {
        val provider = JvmFileOperationsProvider()
        val plainJpeg = ImageMetadataScrubber.scrub(geotaggedJpeg())
        val cases = listOf(
            Triple("stripped JPEG", geotaggedJpeg(), PayloadFile(key = "p0", filePath = "", contentType = "image/jpeg")),
            Triple("JPEG with nothing to strip", plainJpeg, PayloadFile(key = "p0", filePath = "", contentType = "image/jpeg")),
            Triple("format the scrubber can't handle", "GIF89a".encodeToByteArray() + ByteArray(32) { 4 }, PayloadFile(key = "p0", filePath = "", contentType = "image/gif")),
            Triple("image its app keeps", geotaggedJpeg(), PayloadFile(key = "p0", filePath = "", contentType = "image/jpeg", stripMetadata = false)),
            Triple("not an image", "%PDF-1.4".encodeToByteArray(), PayloadFile(key = "p0", filePath = "", contentType = "application/pdf")),
        )
        for ((name, bytes, template) in cases) {
            for (encrypted in listOf(true, false)) {
                val src = provider.writeBytesToTempFile(bytes, "passes_", ".bin")
                val files = CountingFiles(provider, src)
                val payload = template.copy(filePath = src)
                val sent = if (encrypted) {
                    service(files).encryptBundle(Uuid.random(), bundle(payload), KeyHeader.newRandom16().aesKey, this)
                } else {
                    service(files).stageBundle(Uuid.random(), bundle(payload), this)
                }.payloads.single()
                try {
                    val how = if (encrypted) "encrypted" else "public"
                    assertEquals(1, files.reads, "$name, $how: one read of the file")
                    assertEquals(1, files.writes, "$name, $how: one write, the file that's sent")
                } finally {
                    provider.deleteTempFile(src)
                    provider.deleteTempFile(sent.filePath)
                }
            }
        }
    }

    // Same fixture as homebase-api's HeicFixtures: ImageIO's HEIC with GPS, serial and XMP; hvc1 data at 3227.
    @Test
    fun encryptBundle_uploadsHeicWithMetadataStripped() = runTest {
        val provider = JvmFileOperationsProvider()
        val svc = service(provider)
        val key = KeyHeader.newRandom16()

        val original = assertNotNull(javaClass.getResourceAsStream("/geotagged_rotated.heic")).readBytes()
        val src = provider.writeBytesToTempFile(original, "scrub_src_", ".heic")
        var staged: String? = null
        try {
            val payload = svc.encryptBundle(
                Uuid.random(),
                bundle(PayloadFile(key = "p0", filePath = src, contentType = "image/heic")),
                key.aesKey,
                this,
            ).payloads.single()
            staged = payload.filePath

            val expected = ImageMetadataScrubber.scrub(original)
            assertContentEquals(
                KeyHeader(aesKey = key.aesKey, iv = assertNotNull(payload.iv)).encryptDataAes(expected),
                File(staged).readBytes(),
                "uploaded ciphertext must be of the SCRUBBED bytes",
            )
            assertTrue(indexOfSub(original, "SECRETSERIAL".encodeToByteArray()) >= 0, "fixture must carry metadata")
            assertFalse(indexOfSub(expected, "SECRETSERIAL".encodeToByteArray()) >= 0, "EXIF must not reach the wire")
            assertEquals(original.size, expected.size)
            assertContentEquals(original.copyOfRange(3227, original.size), expected.copyOfRange(3227, expected.size))
        } finally {
            provider.deleteTempFile(src)
            staged?.let { provider.deleteTempFile(it) }
        }
    }

    @Test
    fun encryptBundle_leavesNothingBehindButWhatItSends() = runTest {
        val provider = JvmFileOperationsProvider()
        val svc = service(provider)
        val key = KeyHeader.newRandom16()
        val src = provider.writeBytesToTempFile(geotaggedJpeg(), "scrub_src_", ".jpg")
        fun files() = listOf(provider.getCacheDirectory(), provider.getOutboxStagingDirectory())
            .flatMap { File(it).walkTopDown().filter(File::isFile).map(File::getPath).toList() }.toSet()
        val before = files()
        var staged: String? = null
        try {
            staged = svc.encryptBundle(
                Uuid.random(),
                bundle(PayloadFile(key = "p0", filePath = src, contentType = "image/jpeg")),
                key.aesKey,
                this,
            ).payloads.single().filePath

            assertEquals(setOf(File(staged).path), files() - before, "no stripped copy or other temp is left")
        } finally {
            provider.deleteTempFile(src)
            staged?.let { provider.deleteTempFile(it) }
        }
    }

    @Test
    fun encryptBundle_keepsTheMetadataOfAnImageTheAppKeeps() = runTest {
        val provider = JvmFileOperationsProvider()
        val key = KeyHeader.newRandom16()
        val original = geotaggedJpeg()
        val src = provider.writeBytesToTempFile(original, "keep_", ".jpg")
        var staged: String? = null
        try {
            val payload = service(provider).encryptBundle(
                Uuid.random(),
                bundle(PayloadFile(key = "p0", filePath = src, contentType = "image/jpeg", stripMetadata = false)),
                key.aesKey,
                this,
            ).payloads.single()
            staged = payload.filePath
            assertContentEquals(
                KeyHeader(aesKey = key.aesKey, iv = assertNotNull(payload.iv)).encryptDataAes(original),
                File(staged).readBytes(),
            )
        } finally {
            provider.deleteTempFile(src)
            staged?.let { provider.deleteTempFile(it) }
        }
    }

    @Test
    fun encryptBundle_nonImagePayloadIsUntouched() = runTest {
        val provider = JvmFileOperationsProvider()
        val svc = service(provider)
        val key = KeyHeader.newRandom16()
        // A PDF whose bytes happen to begin like a JPEG must still ship verbatim — the
        // scrub is gated on contentType, and this pins that gate.
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + "not really an image".encodeToByteArray()
        val src = provider.writeBytesToTempFile(bytes, "doc_", ".pdf")
        var staged: String? = null
        try {
            val payload = svc.encryptBundle(
                Uuid.random(),
                bundle(PayloadFile(key = "p0", filePath = src, contentType = "application/pdf")),
                key.aesKey,
                this,
            ).payloads.single()
            staged = payload.filePath
            assertContentEquals(
                KeyHeader(aesKey = key.aesKey, iv = assertNotNull(payload.iv)).encryptDataAes(bytes),
                File(staged).readBytes(),
            )
        } finally {
            provider.deleteTempFile(src)
            staged?.let { provider.deleteTempFile(it) }
        }
    }

    @Test
    fun encryptBundle_unsupportedImageFormat_goesOutUnchangedWithAWarning() = runTest {
        val provider = JvmFileOperationsProvider()
        val svc = service(provider)
        val key = KeyHeader.newRandom16()
        val gif = "GIF89a".encodeToByteArray() + ByteArray(32) { 4 }
        val src = provider.writeBytesToTempFile(gif, "unsupported_", ".gif")
        var staged: String? = null
        val warnings = mutableListOf<String>()
        val previousWriters = Logger.config.logWriterList
        Logger.setLogWriters(
            object : LogWriter() {
                override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                    if (severity >= Severity.Warn) warnings += message
                }
            }
        )
        try {
            val payload = svc.encryptBundle(
                Uuid.random(),
                bundle(PayloadFile(key = "p0", filePath = src, contentType = "image/gif")),
                key.aesKey,
                this,
            ).payloads.single()
            staged = payload.filePath
            assertContentEquals(
                KeyHeader(aesKey = key.aesKey, iv = assertNotNull(payload.iv)).encryptDataAes(gif),
                File(staged).readBytes(),
            )
        } finally {
            Logger.setLogWriters(previousWriters)
            provider.deleteTempFile(src)
            staged?.let { provider.deleteTempFile(it) }
        }
        val warning = warnings.single()
        assertTrue("image/gif" in warning && "intact" in warning, warning)
    }

    @Test
    fun anImageHeldInMemoryIsStrippedAndAnythingElseLeftAlone() = runTest {
        val jpeg = geotaggedJpeg()
        assertContentEquals(ImageMetadataScrubber.scrub(jpeg), MediaMetadataStripper.imageBytes(jpeg, "image/jpeg"))
        assertFalse(indexOfSub(MediaMetadataStripper.imageBytes(jpeg, "image/jpeg"), gpsMarker) >= 0)
        val pdf = "%PDF-1.4".encodeToByteArray()
        assertContentEquals(pdf, MediaMetadataStripper.imageBytes(pdf, "application/pdf"))
    }

    private fun indexOfSub(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
