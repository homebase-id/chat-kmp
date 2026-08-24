package id.homebase.upload

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.image.ImageMetadataScrubber
import id.homebase.api.video.VideoPayloadProcessor
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    fun encryptBundle_leavesNoScrubbedTempBehind() = runTest {
        val provider = JvmFileOperationsProvider()
        val svc = service(provider)
        val key = KeyHeader.newRandom16()
        val src = provider.writeBytesToTempFile(geotaggedJpeg(), "scrub_src_", ".jpg")
        var staged: String? = null
        try {
            staged = svc.encryptBundle(
                Uuid.random(),
                bundle(PayloadFile(key = "p0", filePath = src, contentType = "image/jpeg")),
                key.aesKey,
                this,
            ).payloads.single().filePath

            val leftovers = File(provider.getCacheDirectory())
                .listFiles { f -> f.name.startsWith("scrubbed_") }
                ?.toList()
                .orEmpty()
            assertTrue(leftovers.isEmpty(), "scrubbed temp must be reaped, found: $leftovers")
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

    private fun indexOfSub(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
