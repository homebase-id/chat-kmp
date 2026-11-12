package id.homebase.api.image

import id.homebase.api.lib.image.ImageFormatDetector
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the metadata scrub (#1297). Builds JPEG/PNG byte streams by hand rather than shipping
 * binary fixtures, so the exact segments under test are visible here.
 */
class ImageMetadataScrubberTest {

    // --- builders -------------------------------------------------------------

    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) +
            payload
    }

    /** An EXIF APP1 payload with Orientation and a GPS IFD pointer, little-endian. */
    private fun exifPayload(orientation: Int): ByteArray {
        val header = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
        val tiff = mutableListOf<Byte>()
        tiff += listOf(0x49, 0x49, 0x2A, 0x00).map { it.toByte() }        // II, 42
        tiff += listOf(0x08, 0x00, 0x00, 0x00).map { it.toByte() }        // IFD0 @ 8
        tiff += listOf(0x02, 0x00).map { it.toByte() }                    // 2 entries
        // Orientation
        tiff += listOf(0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00).map { it.toByte() }
        tiff += listOf(orientation, 0x00, 0x00, 0x00).map { it.toByte() }
        // GPSInfo IFD pointer (tag 0x8825) — the thing that must not survive
        tiff += listOf(0x25, 0x88, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00).map { it.toByte() }
        tiff += listOf(0x26, 0x00, 0x00, 0x00).map { it.toByte() }
        tiff += listOf(0x00, 0x00, 0x00, 0x00).map { it.toByte() }        // no next IFD
        // GPS IFD at 0x26: one entry, GPSLatitude-ish marker bytes
        tiff += listOf(0x01, 0x00).map { it.toByte() }
        tiff += listOf(0x02, 0x00, 0x05, 0x00, 0x01, 0x00, 0x00, 0x00).map { it.toByte() }
        tiff += listOf(0x37, 0x37, 0x34, 0x39).map { it.toByte() }        // "7749"
        tiff += listOf(0x00, 0x00, 0x00, 0x00).map { it.toByte() }
        return header + tiff.toByteArray()
    }

    private val scanData = ByteArray(64) { (it % 251).toByte() }

    private fun jpeg(
        withExif: Boolean = true,
        orientation: Int = 6,
        withJfif: Boolean = true,
        withIcc: Boolean = true,
        withComment: Boolean = true,
    ): ByteArray {
        var out = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) // SOI
        if (withJfif) out += seg(0xE0, "JFIF".encodeToByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))
        if (withExif) out += seg(0xE1, exifPayload(orientation))
        if (withComment) out += seg(0xFE, "Shot on SecretCamera by Jane".encodeToByteArray())
        if (withIcc) out += seg(0xE2, "ICC_PROFILE".encodeToByteArray() + ByteArray(8) { 9 })
        out += seg(0xDB, ByteArray(65) { 3 })              // DQT — must survive
        out += seg(0xC0, byteArrayOf(8, 0, 16, 0, 16, 1, 0x11, 0)) // SOF0
        out += byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 1, 1, 0, 0, 0x3F, 0) // SOS
        out += scanData
        out += byteArrayOf(0xFF.toByte(), 0xD9.toByte())   // EOI
        return out
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val len = data.size
        return byteArrayOf(
            ((len shr 24) and 0xFF).toByte(), ((len shr 16) and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte(),
        ) + type.encodeToByteArray() + data + byteArrayOf(0, 0, 0, 0) // crc placeholder
    }

    private fun png(withExif: Boolean = true): ByteArray {
        var out = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        out += pngChunk("IHDR", ByteArray(13) { 1 })
        if (withExif) out += pngChunk("eXIf", exifPayload(1))
        if (withExif) out += pngChunk("tEXt", "SoftwareSecretApp".encodeToByteArray())
        out += pngChunk("IDAT", ByteArray(32) { 7 })
        out += pngChunk("IEND", ByteArray(0))
        return out
    }

    private fun ByteArray.containsSub(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    // --- JPEG -----------------------------------------------------------------

    @Test
    fun jpeg_dropsExifAndComment_keepsPixelData() {
        val src = jpeg()
        val out = ImageMetadataScrubber.scrub(src)

        assertFalse(out.containsSub("7749".encodeToByteArray()), "GPS bytes must not survive")
        assertFalse(
            out.containsSub("Shot on SecretCamera by Jane".encodeToByteArray()),
            "comment must not survive",
        )
        assertTrue(out.containsSub(scanData), "compressed pixel data must be bit-identical")
        assertTrue(out.size < src.size, "scrubbed output should be smaller")
    }

    @Test
    fun jpeg_preservesOrientation() {
        val out = ImageMetadataScrubber.scrub(jpeg(orientation = 6))
        // A minimal EXIF must remain, carrying Orientation=6 and nothing else.
        assertTrue(out.containsSub("Exif".encodeToByteArray()), "orientation EXIF must be re-emitted")
        assertTrue(
            out.containsSub(byteArrayOf(0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06)),
            "Orientation tag must survive with value 6",
        )
        assertFalse(out.containsSub(byteArrayOf(0x25, 0x88.toByte())), "GPS IFD pointer must be gone")
    }

    @Test
    fun jpeg_defaultOrientation_emitsNoExifAtAll() {
        val out = ImageMetadataScrubber.scrub(jpeg(orientation = 1))
        assertFalse(out.containsSub("Exif".encodeToByteArray()), "orientation 1 needs no EXIF block")
    }

    @Test
    fun jpeg_keepsIccAndJfif() {
        val out = ImageMetadataScrubber.scrub(jpeg())
        assertTrue(out.containsSub("ICC_PROFILE".encodeToByteArray()), "colour profile must survive")
        assertTrue(out.containsSub("JFIF".encodeToByteArray()), "JFIF must survive")
    }

    @Test
    fun jpeg_withNothingToRemove_returnsSameInstance() {
        val clean = jpeg(withExif = false, withComment = false)
        assertSame(clean, ImageMetadataScrubber.scrub(clean), "no-op must not reallocate")
    }

    @Test
    fun jpeg_malformed_returnsInputUnchanged() {
        // Marker byte where a 0xFF is required — must refuse rather than mangle the file.
        val broken = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(40) { 0x11 }
        assertSame(broken, ImageMetadataScrubber.scrub(broken))
    }

    @Test
    fun jpeg_truncated_doesNotThrow() {
        val src = jpeg()
        for (cut in listOf(2, 5, 12, 30, 60, src.size - 3)) {
            val truncated = src.copyOfRange(0, cut)
            ImageMetadataScrubber.scrub(truncated) // must not throw
        }
    }

    // --- PNG ------------------------------------------------------------------

    @Test
    fun png_dropsExifAndTextChunks_keepsImageData() {
        val src = png()
        val out = ImageMetadataScrubber.scrub(src)
        assertFalse(out.containsSub("eXIf".encodeToByteArray()), "eXIf chunk must be gone")
        assertFalse(out.containsSub("SecretApp".encodeToByteArray()), "tEXt chunk must be gone")
        assertTrue(out.containsSub("IDAT".encodeToByteArray()), "image data must survive")
        assertTrue(out.containsSub("IEND".encodeToByteArray()), "IEND must survive")
    }

    @Test
    fun png_withNothingToRemove_returnsSameInstance() {
        val clean = png(withExif = false)
        assertSame(clean, ImageMetadataScrubber.scrub(clean))
    }

    // --- WebP -----------------------------------------------------------------

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun webpChunk(type: String, data: ByteArray): ByteArray =
        type.encodeToByteArray() + le32(data.size) + data +
            if (data.size and 1 == 1) byteArrayOf(0) else ByteArray(0)

    private val vp8Data = ByteArray(40) { (it % 199).toByte() }

    /** WebP stores the raw TIFF block, without the JPEG-style Exif identifier prefix. */
    private fun exifTiff(orientation: Int) = exifPayload(orientation).copyOfRange(6, exifPayload(orientation).size)

    private fun webp(
        extended: Boolean = true,
        withExif: Boolean = true,
        orientation: Int = 6,
        withXmp: Boolean = true,
    ): ByteArray {
        var body = ByteArray(0)
        if (extended) {
            val flags = (if (withExif) 0x08 else 0) or (if (withXmp) 0x04 else 0)
            body += webpChunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0, 0x0F, 0, 0, 0x0F, 0))
        }
        body += webpChunk("VP8 ", vp8Data)
        if (withExif) body += webpChunk("EXIF", exifTiff(orientation))
        if (withXmp) body += webpChunk("XMP ", "<x:xmpmeta>secret-author</x:xmpmeta>".encodeToByteArray())
        return "RIFF".encodeToByteArray() + le32(4 + body.size) + "WEBP".encodeToByteArray() + body
    }

    private fun riffSizeField(b: ByteArray): Int =
        (b[4].toInt() and 0xFF) or ((b[5].toInt() and 0xFF) shl 8) or
            ((b[6].toInt() and 0xFF) shl 16) or ((b[7].toInt() and 0xFF) shl 24)

    private fun vp8xFlags(b: ByteArray): Int {
        var i = 12
        while (i + 8 <= b.size) {
            val type = buildString { for (k in 0 until 4) append((b[i + k].toInt() and 0xFF).toChar()) }
            val size = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (type == "VP8X") return b[i + 8].toInt() and 0xFF
            i += 8 + size + (size and 1)
        }
        return -1
    }

    @Test
    fun webp_dropsExifAndXmp_keepsImageData() {
        val out = ImageMetadataScrubber.scrub(webp(orientation = 1))
        assertFalse(out.containsSub("7749".encodeToByteArray()), "GPS bytes must not survive")
        assertFalse(out.containsSub("secret-author".encodeToByteArray()), "XMP must not survive")
        assertTrue(out.containsSub(vp8Data), "image data must be untouched")
    }

    @Test
    fun webp_clearsVp8xFlagsForRemovedChunks() {
        val out = ImageMetadataScrubber.scrub(webp(orientation = 1))
        val flags = vp8xFlags(out)
        assertTrue(flags >= 0, "VP8X must still be present")
        assertEquals(0, flags and 0x08, "EXIF flag must be cleared")
        assertEquals(0, flags and 0x04, "XMP flag must be cleared")
    }

    @Test
    fun webp_rewritesRiffSize() {
        val out = ImageMetadataScrubber.scrub(webp(orientation = 1))
        assertEquals(
            out.size - 8, riffSizeField(out),
            "RIFF size field must match the shortened file, or decoders reject it",
        )
    }

    @Test
    fun webp_preservesOrientation() {
        val out = ImageMetadataScrubber.scrub(webp(orientation = 6))
        assertTrue(out.containsSub("EXIF".encodeToByteArray()), "orientation EXIF chunk must be re-emitted")
        assertTrue(
            out.containsSub(byteArrayOf(0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06)),
            "Orientation must survive with value 6",
        )
        assertFalse(out.containsSub("7749".encodeToByteArray()), "GPS must still be gone")
        assertEquals(0x08, vp8xFlags(out) and 0x08, "EXIF flag must stay set when EXIF is re-emitted")
        assertEquals(0, vp8xFlags(out) and 0x04, "XMP flag must still be cleared")
        assertEquals(out.size - 8, riffSizeField(out))
    }

    @Test
    fun webp_simpleFormat_withNothingToRemove_returnsSameInstance() {
        val clean = webp(extended = false, withExif = false, withXmp = false)
        assertSame(clean, ImageMetadataScrubber.scrub(clean))
    }

    @Test
    fun webp_truncated_doesNotThrow() {
        val src = webp()
        for (cut in listOf(12, 14, 20, 30, src.size - 3)) {
            ImageMetadataScrubber.scrub(src.copyOfRange(0, cut))
        }
    }

    // --- other formats --------------------------------------------------------

    @Test
    fun unknownFormat_passesThrough() {
        val gif = "GIF89a".encodeToByteArray() + ByteArray(20) { 4 }
        assertSame(gif, ImageMetadataScrubber.scrub(gif))
        val empty = ByteArray(0)
        assertContentEquals(empty, ImageMetadataScrubber.scrub(empty))
    }

    // The send path warns on both, so neither a new format nor a broken file leaks silently.
    @Test
    fun outcome_tellsUnsupportedAndUnparseableApartFromClean() {
        listOf(jpeg(), png(), webp(), HeicFixtures.geotaggedRotated).forEach {
            assertIs<ImageMetadataScrubber.Outcome.Scrubbed>(ImageMetadataScrubber.scrubWithOutcome(it), ImageFormatDetector.detectFormat(it))
        }
        assertEquals(ImageMetadataScrubber.Outcome.NothingToStrip, ImageMetadataScrubber.scrubWithOutcome(jpeg(withExif = false, withComment = false)))
        val avif = HeicFixtures.geotaggedRotated.copyOf().also { "avif".encodeToByteArray().copyInto(it, 8) }
        val bmp = "BM".encodeToByteArray() + ByteArray(30)
        val gif = "GIF89a".encodeToByteArray() + ByteArray(20) { 4 }
        listOf(gif, bmp, avif, ByteArray(0)).forEach {
            assertEquals(ImageMetadataScrubber.Outcome.UnsupportedFormat, ImageMetadataScrubber.scrubWithOutcome(it), it.decodeToString(0, minOf(12, it.size)))
        }
    }

    @Test
    fun malformedFiles_areUnparseable() {
        val hugeChunk = byteArrayOf(0x80.toByte(), 0, 0, 0)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(40) { 0x11 }
        val png = png().copyOfRange(0, 8) + hugeChunk + "tEXt".encodeToByteArray() + ByteArray(8)
        val webp = "RIFF".encodeToByteArray() + le32(20) + "WEBP".encodeToByteArray() +
            "EXIF".encodeToByteArray() + hugeChunk.reversedArray() + ByteArray(8)
        listOf(jpeg, png, webp).forEach {
            assertEquals(ImageMetadataScrubber.Outcome.Unparseable, ImageMetadataScrubber.scrubWithOutcome(it), ImageFormatDetector.detectFormat(it))
            assertSame(it, ImageMetadataScrubber.scrub(it))
        }
    }

    @Test
    fun aFileCutOffInsideItsMetadata_dropsTheRest() {
        val jpegCutInExif = jpeg().copyOfRange(0, 30)
        val jpegOut = assertIs<ImageMetadataScrubber.Outcome.Scrubbed>(ImageMetadataScrubber.scrubWithOutcome(jpegCutInExif)).bytes
        assertFalse(jpegOut.containsSub(byteArrayOf(0xFF.toByte(), 0xE1.toByte())), "truncated EXIF survived")

        val fullWebp = webp()
        val webpCutInXmp = fullWebp.copyOfRange(0, fullWebp.size - 3)
        val webpOut = assertIs<ImageMetadataScrubber.Outcome.Scrubbed>(ImageMetadataScrubber.scrubWithOutcome(webpCutInXmp)).bytes
        assertFalse(webpOut.containsSub("secret-author".encodeToByteArray()), "truncated XMP survived")
    }
}
