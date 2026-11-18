package id.homebase.api.media

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.OkioFileOperationsProvider
import id.homebase.api.image.ImageMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class MediaMetadataReaderTest {

    private fun be32(v: Int) = ByteArray(4) { (v ushr (24 - 8 * it)).toByte() }
    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = body.fold(ByteArray(0)) { all, b -> all + b }
        val name = ByteArray(4) { type[it].code.toByte() }
        return be32(8 + content.size) + name + content
    }

    // Android and ffmpeg: moov/udta/©xyz with a length, a language and the text.
    private fun androidMoov(text: String) = text.encodeToByteArray().let { box("moov", box("udta", box("©xyz", be16(it.size), be16(0x15C7), it))) }

    // iPhone: moov/meta with hdlr, keys naming the location key second, and ilst holding it under index 2.
    private fun appleMoov(text: String): ByteArray {
        fun key(name: String) = name.encodeToByteArray().let { be32(8 + it.size) + "mdta".encodeToByteArray() + it }
        val keys = box("keys", ByteArray(4), be32(2), key("com.apple.quicktime.make"), key("com.apple.quicktime.location.ISO6709"))
        fun item(index: Int, value: String) = be32(8 + 16 + value.length) + be32(index) + box("data", be32(1), be32(0), value.encodeToByteArray())
        val ilst = box("ilst", item(1, "Apple"), item(2, text))
        return box("moov", box("meta", box("hdlr", ByteArray(24)), keys, ilst))
    }

    // A camera's layout: the moov after the media.
    private fun video(moov: ByteArray, mdatBytes: Int = 100_000) =
        box("ftyp", "qt  ".encodeToByteArray(), ByteArray(4)) + box("mdat", ByteArray(mdatBytes)) + moov

    private fun files(bytes: ByteArray) = OkioFileOperationsProvider(FakeFileSystem().also { it.write("/v.mp4".toPath()) { write(bytes) } }, "/cache")

    private suspend fun location(bytes: ByteArray) = MediaMetadataReader(files(bytes)).videoLocation("/v.mp4")

    @Test
    fun androidsLocationIsReadFromAMoovAfterTheMediaByItsHeadersAlone() = runTest {
        val bytes = video(androidMoov("+37.7749-122.4194/"), mdatBytes = 10_000_000)
        var read = 0L
        var reads = 0
        val okio = files(bytes)
        val counting = object : FileOperationsProvider by okio {
            override suspend fun readFileRange(path: String, offset: Long, length: Int) =
                okio.readFileRange(path, offset, length).also { read += it.size; reads++ }
        }
        assertEquals(MediaLocation(37.7749, -122.4194), MediaMetadataReader(counting).videoLocation("/v.mp4"))
        assertEquals(2, reads, "the start of the file, then the moov: each read may open the file again")
        assertTrue(read <= 512 * 1024, "read $read bytes of ${bytes.size}")
    }

    @Test
    fun aMoovIsFoundWhenTheFileSizeIsntKnown() = runTest {
        val okio = files(video(androidMoov("+37.7749-122.4194/")))
        val unsized = object : FileOperationsProvider by okio {
            override fun getFileSize(path: String) = 0L
        }
        assertEquals(MediaLocation(37.7749, -122.4194), MediaMetadataReader(unsized).videoLocation("/v.mp4"))
    }

    @Test
    fun anIphonesLocationKeyIsRead() = runTest {
        assertEquals(MediaLocation(55.6761, 12.5683, 14.2), location(video(appleMoov("+55.6761+012.5683+014.200/"))))
    }

    @Test
    fun aVideoWithNoLocationHasNone() = runTest {
        assertNull(location(video(box("moov", box("udta")))))
        assertNull(location(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(10))))
    }

    @Test
    fun aFileThatCantBeReadHasNoMetadataRatherThanAnError() = runTest {
        val reader = MediaMetadataReader(files(ByteArray(0)))
        assertNull(reader.videoLocation("/missing.mp4"))
        assertNull(reader.image("/missing.jpg"))
        assertNull(MediaMetadataReader(files(ByteArray(64) { it.toByte() })).image("/v.mp4"))
    }

    @Test
    fun anImagesLocationIsItsGpsWhenItHasBoth() {
        val photo = ImageMetadata(latitude = 59.9139, longitude = 10.7522, altitudeMeters = 23.0)
        assertEquals(MediaLocation(59.9139, 10.7522, 23.0), photo.location)
        assertNull(photo.copy(longitude = null).location)
    }

    @Test
    fun iso6709IsReadAsCamerasWriteIt() {
        assertEquals(MediaLocation(-33.8688, 151.2093, -5.0), MediaLocation.parse("-33.8688+151.2093-005.000/"))
        assertNull(MediaLocation.parse("+91.0000+000.0000/"), "no latitude past the pole")
        assertNull(MediaLocation.parse("nowhere"))
    }
}
