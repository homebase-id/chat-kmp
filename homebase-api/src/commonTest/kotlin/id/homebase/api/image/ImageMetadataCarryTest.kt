package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// A re-encode that keeps metadata carries the source's EXIF and XMP (THUMBNAILS.md, "Metadata").
class ImageMetadataCarryTest {

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = ByteArray(4) { (v ushr (8 * it)).toByte() }
    private fun entry(tag: Int, type: Int, count: Int, value: ByteArray) = le16(tag) + le16(type) + le32(count) + value.copyOf(4)

    // IFD0 (Orientation 6, Exif and GPS pointers) at 8, Exif IFD (4000 x 3000) at 50, GPS IFD at 80 with
    // its text at 98, and IFD1 at 106 pointing at a 20-byte embedded thumbnail at 124.
    private val tiff: ByteArray = run {
        val ifd0 = le16(3) + entry(0x0112, 3, 1, le16(6)) + entry(0x8769, 4, 1, le32(50)) + entry(0x8825, 4, 1, le32(80)) + le32(106)
        val exifIfd = le16(2) + entry(0xA002, 4, 1, le32(4000)) + entry(0xA003, 4, 1, le32(3000)) + le32(0)
        val gpsIfd = le16(1) + entry(0x0002, 2, 8, le32(98)) + le32(0)
        val ifd1 = le16(1) + entry(0x0201, 4, 1, le32(124)) + le32(0)
        "II".encodeToByteArray() + le16(42) + le32(8) + ifd0 + exifIfd + gpsIfd + "GPS-7749".encodeToByteArray() + ifd1 +
            "THUMBTHUMBTHUMBTHUMB".encodeToByteArray()
    }
    private val xmp = "<x:xmpmeta>creator: me</x:xmpmeta>".encodeToByteArray()

    private fun app1(payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        app1("Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff) +
        app1("http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray() + xmp) +
        byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = "WEBP".encodeToByteArray() + chunks.fold(ByteArray(0)) { all, c -> all + c }
        return "RIFF".encodeToByteArray() + le32(body.size) + body
    }
    private fun chunk(type: String, data: ByteArray) = type.encodeToByteArray() + le32(data.size) + data + (if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
    private val lossyWebp = riff(chunk("VP8 ", ByteArray(10) { 7 }))

    // Read back the way every JPEG reader does, in either byte order.
    private fun orientationOf(tiff: ByteArray) =
        readJpegExifOrientation(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1("Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff))

    private fun ByteArray.contains(needle: ByteArray) = (0..size - needle.size).any { at -> needle.indices.all { this[at + it] == needle[it] } }
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)

    @Test
    fun aJpegsExifAndXmpAreRead() {
        val blocks = assertNotNull(ImageMetadataScrubber.readBlocks(jpeg))
        assertContentEquals(tiff, blocks.exif)
        assertContentEquals(xmp, blocks.xmp)
    }

    @Test
    fun theFinalImageCarriesTheSourcesMetadataMadeRightForIt() {
        val out = withSourceMetadata(lossyWebp, jpeg, 640, 480)

        assertEquals("VP8X", out.decodeToString(12, 16), "the extended header announces the metadata")
        assertEquals(0x0C, out[20].toInt() and 0x0C, "EXIF and XMP flags")
        assertEquals(639, u16(out, 24) or ((out[26].toInt() and 0xFF) shl 16))
        assertEquals(479, u16(out, 27) or ((out[29].toInt() and 0xFF) shl 16))
        assertEquals(out.size - 8, u32(out, 4), "the RIFF size covers the new chunks")

        val carried = assertNotNull(ImageMetadataScrubber.readBlocks(out))
        val exif = assertNotNull(carried.exif)
        assertEquals(1, orientationOf(exif), "upright pixels: Orientation 1")
        assertEquals(640, u32(exif, 50 + 2 + 8))
        assertEquals(480, u32(exif, 50 + 2 + 12 + 8))
        assertTrue(exif.contains("GPS-7749".encodeToByteArray()), "location kept")
        assertFalse(exif.contains("THUMB".encodeToByteArray()), "the camera's embedded thumbnail isn't copied")
        assertEquals(0, u32(exif, 8 + 2 + 3 * 12), "IFD1 unlinked")
        assertContentEquals(xmp, carried.xmp)
    }

    @Test
    fun anExtendedWebpGainsTheChunksAndFlags() {
        val vp8x = ByteArray(10).also { it[0] = 0x20 } // already has an ICC profile flag
        val out = withSourceMetadata(riff(chunk("VP8X", vp8x), chunk("VP8 ", ByteArray(10))), jpeg, 640, 480)

        assertEquals(0x2C, out[20].toInt() and 0xFF, "its own flags kept, EXIF and XMP added")
        assertNotNull(ImageMetadataScrubber.readBlocks(out)?.exif)
    }

    @Test
    fun aHeicsMetadataIsCarriedToo() {
        val heic = HeicFixtures.geotaggedRotated
        val blocks = assertNotNull(ImageMetadataScrubber.readBlocks(heic))
        assertTrue(assertNotNull(blocks.exif).contains("SecretCamera".encodeToByteArray()))
        assertNotNull(blocks.xmp)

        val exif = assertNotNull(ImageMetadataScrubber.readBlocks(withSourceMetadata(lossyWebp, heic, 64, 32))?.exif)
        assertEquals(1, orientationOf(exif), "upright pixels: Orientation 1")
    }

    @Test
    fun aSourceWithNoMetadataLeavesTheFinalImageAlone() {
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0, 2, 0xFF.toByte(), 0xD9.toByte())
        assertSame(lossyWebp, withSourceMetadata(lossyWebp, plain, 640, 480))
    }
}
