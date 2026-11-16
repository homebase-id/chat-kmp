package id.homebase.api.image

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UltraHdrTest {
    private val metadata = GainmapMetadata(
        gainMapMin = listOf(0.0),
        gainMapMax = listOf(2.3),
        gamma = listOf(1.0),
        offsetSdr = listOf(1.0 / 64),
        offsetHdr = listOf(1.0 / 64),
        hdrCapacityMin = 0.0,
        hdrCapacityMax = 2.3,
    )

    @Test
    fun aJoinedFileGivesBackItsGainmapAndMetadata() {
        val joined = UltraHdr.join(jpeg(1, 2, 3), jpeg(9, 8), metadata)

        val gainmap = assertNotNull(UltraHdr.gainmapOf(joined))
        assertClose(metadata, gainmap.metadata)
        assertContentEquals(scan(9, 8), gainmap.jpeg.copyOfRange(gainmap.jpeg.size - scan(9, 8).size, gainmap.jpeg.size))
        // The gain map is the file's tail, right after the primary image's EOI.
        assertEquals(joined.size.toLong() - 1, UltraHdr.gainmapRange(joined)!!.last)
        assertTrue(joined.decodeToString().contains("Item:Length=\"${gainmap.jpeg.size}\""), "the directory gives the gain map's length")
    }

    @Test
    fun jfifStaysFirstAndTheMpfIndexSitsRightBeforeTheScan() {
        val joined = UltraHdr.join(jpeg(1), jpeg(2), metadata)
        val primaryEnd = UltraHdr.gainmapRange(joined)!!.first.toInt()
        val primary = joined.copyOfRange(0, primaryEnd)

        assertEquals(0xE0, primary[3].toInt() and 0xFF, "JFIF right after SOI")
        val sos = primary.indexOfMarker(0xDA)
        val mpfStart = sos - 90 // marker, length, "MPF\0", TIFF header, three tags, two entries
        assertEquals("MPF", primary.decodeToString(mpfStart + 4, mpfStart + 7), "the MPF segment ends where the scan starts")
    }

    @Test
    fun theXmpAloneIsEnoughWithoutTheIsoBlock() {
        val xmp = """<x:xmpmeta><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"
            hdrgm:GainMapMax="2.3" hdrgm:HDRCapacityMax="2.3"/></x:xmpmeta>"""
        assertClose(metadata, assertNotNull(HdrgmXmp.decode(xmp)), "the format's defaults fill the rest")
    }

    @Test
    fun xmpElementsAndPerChannelSequencesAreRead() {
        val xmp = """<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/">
            <hdrgm:GainMapMax><rdf:Seq><rdf:li>1.5</rdf:li><rdf:li>2</rdf:li><rdf:li>2.5</rdf:li></rdf:Seq></hdrgm:GainMapMax>
            <hdrgm:HDRCapacityMax>2.5</hdrgm:HDRCapacityMax></rdf:Description>"""
        val read = assertNotNull(HdrgmXmp.decode(xmp))
        assertEquals(listOf(1.5, 2.0, 2.5), read.gainMapMax)
        assertEquals(2.5, read.hdrCapacityMax)
    }

    @Test
    fun anHdrBaseImageIsNotRead() {
        val xmp = """<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:GainMapMax="2"
            hdrgm:HDRCapacityMax="2" hdrgm:BaseRenditionIsHDR="True"/>"""
        assertNull(HdrgmXmp.decode(xmp))
    }

    @Test
    fun theIsoBlockRoundTripsOneAndThreeChannels() {
        assertClose(metadata, assertNotNull(IsoGainmapMetadata.decode(IsoGainmapMetadata.encode(metadata))))
        assertEquals(61, IsoGainmapMetadata.encode(metadata).size, "one channel, as libultrahdr writes it")

        val rgb = metadata.copy(gainMapMax = listOf(1.0, 2.0, 3.0), gainMapMin = listOf(-0.5, 0.0, 0.0))
        val encoded = IsoGainmapMetadata.encode(rgb)
        assertEquals(141, encoded.size)
        assertClose(rgb, assertNotNull(IsoGainmapMetadata.decode(encoded)))
    }

    @Test
    fun rejoiningDropsTheOldIndexAndMetadata() {
        val once = UltraHdr.join(jpeg(1), jpeg(2), metadata)
        val primary = once.copyOfRange(0, UltraHdr.gainmapRange(once)!!.first.toInt())
        val gainmap = assertNotNull(UltraHdr.gainmapOf(once)).jpeg
        val twice = UltraHdr.join(primary, gainmap, metadata)
        assertContentEquals(once, twice)
    }

    @Test
    fun addingExifKeepsTheGainmapFindable() {
        val joined = UltraHdr.join(jpeg(1), jpeg(2), metadata)
        val tiff = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
        val withExif = ImageMetadataScrubber.withExif(joined, tiff)
        assertClose(metadata, assertNotNull(UltraHdr.gainmapOf(withExif)).metadata)
    }

    @Test
    fun scrubbingKeepsTheGainmapFindable() {
        val joined = UltraHdr.join(jpeg(1), jpeg(2), metadata)
        val scrubbed = ImageMetadataScrubber.scrub(joined)
        assertClose(metadata, assertNotNull(UltraHdr.gainmapOf(scrubbed)).metadata)
    }

    @Test
    fun aPlainJpegHasNoGainmap() {
        assertNull(UltraHdr.gainmapOf(jpeg(1, 2)))
    }

    private fun scan(vararg data: Int) = bytes(0xFF, 0xDA, 0, 4, 0, 0) + bytes(*data) + bytes(0xFF, 0xD9)

    private fun jpeg(vararg data: Int) = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0x4A, 0x46, 0xFF, 0xDB, 0, 4, 1, 2) + scan(*data)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.indexOfMarker(marker: Int) = (0 until size - 1).first { this[it] == 0xFF.toByte() && (this[it + 1].toInt() and 0xFF) == marker }

    private fun assertClose(expected: GainmapMetadata, actual: GainmapMetadata, message: String = "") {
        // One value stands for all three channels.
        fun close(a: List<Double>, b: List<Double>) = (0 until maxOf(a.size, b.size)).all { abs(a[it % a.size] - b[it % b.size]) < 1e-5 }
        assertTrue(
            close(expected.gainMapMin, actual.gainMapMin) && close(expected.gainMapMax, actual.gainMapMax) &&
                close(expected.gamma, actual.gamma) && close(expected.offsetSdr, actual.offsetSdr) &&
                close(expected.offsetHdr, actual.offsetHdr) && abs(expected.hdrCapacityMin - actual.hdrCapacityMin) < 1e-5 &&
                abs(expected.hdrCapacityMax - actual.hdrCapacityMax) < 1e-5,
            "$message: expected $expected, got $actual",
        )
    }
}
