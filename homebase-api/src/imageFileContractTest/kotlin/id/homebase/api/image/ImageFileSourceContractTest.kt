package id.homebase.api.image

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.OkioFileOperationsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A photo from a path makes what its bytes make, and on the phones without the file ever read whole. */
class ImageFileSourceContractTest {
    private class CountingFileOps(private val inner: FileOperationsProvider) : FileOperationsProvider by inner {
        var wholeReads = 0

        override suspend fun readFileBytes(path: String): ByteArray = inner.readFileBytes(path).also { wholeReads++ }

        override fun readFileAsFlow(path: String, chunkSize: Int): Flow<ByteArray> =
            inner.readFileAsFlow(path, chunkSize).also { wholeReads++ }
    }

    private val ops = CountingFileOps(OkioFileOperationsProvider(FileSystem.SYSTEM, FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()))
    private val written = mutableListOf<String>()

    @AfterTest
    fun cleanUp() = runTest { written.forEach { ops.deleteTempFile(it) } }

    // Orientation 6, so the displayed size is the stored one turned on its side.
    private suspend fun rotatedJpeg(): Pair<ByteArray, String> {
        val pixels = IntArray(1600 * 1200) { i -> (0xFF shl 24) or ((i % 1600) % 256 shl 16) or ((i / 1600) % 256 shl 8) or 90 }
        val png = ImageUtils.encodeArgbToPng(ArgbImage(pixels, 1600, 1200))
        val jpeg = ImageUtils.resizePreserveAspect(png, 1600, 1200, ImageFormat.JPEG, 90).bytes
        val tiff = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0)
        val body = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val length = body.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
        val bytes = jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
        return bytes to ops.writeBytesToTempFile(bytes, "file_source_", ".jpg").also { written += it }
    }

    private fun opensDirectly(path: String) = openImageFile(path)?.use { true } ?: false

    @Test
    fun aPathMakesTheSameImagesAsItsBytes() = runTest {
        val (bytes, path) = rotatedJpeg()

        val fromBytes = createMediaImages(bytes, "k", MediaQuality.STANDARD, readMetadata = true)
        val fromPath = createMediaImages(path, ops, "k", MediaQuality.STANDARD, readMetadata = true)

        assertEquals(ImageSize(1200, 1600), fromPath.displayedSize)
        assertEquals(fromBytes.displayedSize, fromPath.displayedSize)
        assertEquals(fromBytes.thumbnails.map { it.pixelWidth to it.pixelHeight }, fromPath.thumbnails.map { it.pixelWidth to it.pixelHeight })
        fromBytes.thumbnails.zip(fromPath.thumbnails).forEach { (a, b) -> assertContentEquals(a.thumbnailBytes, b.thumbnailBytes) }
        assertContentEquals(assertNotNull(fromBytes.finalImage).bytes, assertNotNull(fromPath.finalImage).bytes)
        assertEquals(fromBytes.tiny, fromPath.tiny)
        assertEquals("image/jpeg", fromPath.sourceFormat)
        assertEquals(bytes.size.toLong(), fromPath.sourceByteCount)
        assertEquals(fromBytes.metadata, fromPath.metadata)
        assertEquals(6, fromPath.metadata?.orientation)
    }

    @Test
    fun onlyAPlatformThatCantOpenTheFileReadsItWhole() = runTest {
        val (_, path) = rotatedJpeg()
        val expectedReads = if (opensDirectly(path)) 0 else 1

        createMediaImages(path, ops, "k", MediaQuality.STANDARD, readMetadata = true)
        assertEquals(expectedReads, ops.wholeReads, "thumbnails and EXIF")

        ops.wholeReads = 0
        assertEquals(6, readImageMetadata(path, ops)?.orientation)
        assertEquals(expectedReads, ops.wholeReads, "EXIF alone")
    }
}
