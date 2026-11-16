package id.homebase.api.video

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.OkioFileOperationsProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class VideoLocationReaderTest {

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

    private fun file(moov: ByteArray, mdatBytes: Int = 100_000) =
        box("ftyp", "qt  ".encodeToByteArray(), ByteArray(4)) + box("mdat", ByteArray(mdatBytes)) + moov

    private fun read(bytes: ByteArray, chunk: Int): VideoLocation? =
        VideoLocationReader().apply { bytes.asList().chunked(chunk).forEach { feed(it.toByteArray()) } }.location

    @Test
    fun androidsLocationIsReadAfterTheMediaWhateverTheChunks() {
        val bytes = file(androidMoov("+37.7749-122.4194/"))
        for (chunk in listOf(1, 7, 65_536, bytes.size)) {
            assertEquals(VideoLocation(37.7749, -122.4194), read(bytes, chunk), "chunks of $chunk")
        }
    }

    @Test
    fun aMoovAfterTheMediaIsFoundByItsHeadersAlone() = runTest {
        val bytes = file(androidMoov("+37.7749-122.4194/"), mdatBytes = 10_000_000)
        var read = 0L
        val okio = OkioFileOperationsProvider(FakeFileSystem().also { it.write("/v.mp4".toPath()) { write(bytes) } }, "/cache")
        val files = object : FileOperationsProvider by okio {
            override suspend fun readFileRange(path: String, offset: Long, length: Int) =
                okio.readFileRange(path, offset, length).also { read += it.size }
        }
        assertEquals(VideoLocation(37.7749, -122.4194), readMoov(files, "/v.mp4")?.let(::locationInMoov))
        assertTrue(read < 64 * 1024, "read $read bytes of ${bytes.size}")
    }

    @Test
    fun aMoovIsFoundWhenTheFileSizeIsntKnown() = runTest {
        val bytes = file(androidMoov("+37.7749-122.4194/"))
        val fake = FakeFileSystem().also { it.write("/v.mp4".toPath()) { write(bytes) } }
        val files = object : FileOperationsProvider by OkioFileOperationsProvider(fake, "/cache") {
            override fun getFileSize(path: String) = 0L
        }
        assertEquals(VideoLocation(37.7749, -122.4194), readMoov(files, "/v.mp4")?.let(::locationInMoov))
    }

    @Test
    fun chunksFedAtAnOffsetReadTheSameAsWholeChunks() {
        val bytes = file(androidMoov("+37.7749-122.4194/"))
        val reader = VideoLocationReader()
        for (at in bytes.indices step 1000) {
            val padded = ByteArray(7) + bytes.copyOfRange(at, minOf(at + 1000, bytes.size)) + ByteArray(5)
            reader.feed(padded, 7, padded.size - 12)
        }
        assertEquals(VideoLocation(37.7749, -122.4194), reader.location)
    }

    @Test
    fun anIphonesLocationKeyIsRead() {
        assertEquals(VideoLocation(55.6761, 12.5683, 14.2), read(file(appleMoov("+55.6761+012.5683+014.200/")), 4096))
    }

    @Test
    fun aVideoWithNoLocationHasNone() {
        assertNull(read(file(box("moov", box("udta"))), 4096))
        assertNull(read(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(10)), 4096))
    }

    @Test
    fun iso6709IsReadAsCamerasWriteIt() {
        assertEquals(VideoLocation(-33.8688, 151.2093, -5.0), VideoLocation.parse("-33.8688+151.2093-005.000/"))
        assertNull(VideoLocation.parse("+91.0000+000.0000/"), "no latitude past the pole")
        assertNull(VideoLocation.parse("nowhere"))
    }
}
