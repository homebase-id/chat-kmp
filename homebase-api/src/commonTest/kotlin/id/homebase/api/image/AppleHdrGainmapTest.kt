package id.homebase.api.image

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleHdrGainmapTest {
    private fun assertNear(expected: Double, actual: Double?, label: String) =
        assertTrue(actual != null && abs(expected - actual) < 1e-6, "$label: expected $expected, was $actual")

    @Test
    fun theMakerNoteGivesTheHeadroomByApplesFormula() {
        assertNear(2.0.pow(1.8 - 20 * 0.005), AppleHdrGainmap.headroom(0.9, 0.005), "33 < 1, 48 <= 0.01")
        assertNear(2.0.pow(1.601 - 0.101 * 0.5), AppleHdrGainmap.headroom(0.9, 0.5), "33 < 1, 48 > 0.01")
        assertNear(2.0.pow(3.0 - 70 * 0.005), AppleHdrGainmap.headroom(1.01, 0.005), "33 >= 1, 48 <= 0.01")
        assertNear(2.0.pow(2.303 - 0.303 * 0.5), AppleHdrGainmap.headroom(1.01, 0.5), "33 >= 1, 48 > 0.01")
        assertNull(AppleHdrGainmap.headroom(1.01, 10.0), "no stops is no HDR")
    }

    @Test
    fun theHeadroomBesideTheMapIsReadInEitherXmpForm() {
        val ns = """xmlns:HDRGainMap="${AppleHdrGainmap.NAMESPACE}""""
        assertNear(3.5, AppleHdrGainmap.headroomFromXmp("<x $ns><HDRGainMap:HDRGainMapHeadroom>3.5</HDRGainMap:HDRGainMapHeadroom></x>"), "element")
        assertNear(2.25, AppleHdrGainmap.headroomFromXmp("""<x $ns HDRGainMap:HDRGainMapHeadroom="2.25"/>"""), "attribute")
        assertNull(AppleHdrGainmap.headroomFromXmp("<x $ns><HDRGainMap:HDRGainMapVersion>65536</HDRGainMap:HDRGainMapVersion></x>"))
    }

    @Test
    fun theNamespaceIsFoundUnderAnyPrefix() {
        val xml = """<rdf:Description xmlns:hg0="${AppleHdrGainmap.NAMESPACE}"><hg0:HDRGainMapHeadroom>2.718281828459045</hg0:HDRGainMapHeadroom></rdf:Description>"""
        assertNear(2.718281828459045, AppleHdrGainmap.headroomFromXmp(xml), "another prefix")
        assertNull(AppleHdrGainmap.headroomFromXmp("<HDRGainMap:HDRGainMapHeadroom>3.5</HDRGainMap:HDRGainMapHeadroom>"), "an unbound prefix isn't Apple's")
    }

    @Test
    fun reEncodedValuesGiveTheSameBoostUnderTheIsoFormula() {
        val headroom = 4.0
        val lut = AppleHdrGainmap.isoValues(headroom)
        val metadata = AppleHdrGainmap.metadata(headroom)
        assertEquals(0, lut[0].toInt() and 0xFF)
        assertEquals(255, lut[255].toInt() and 0xFF)
        assertNear(2.0, metadata.hdrCapacityMax, "capacity")
        for (v in 0..255) {
            val linear = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            val apple = 1 + (headroom - 1) * linear
            val iso = 2.0.pow(metadata.gainMapMax[0] * (lut[v].toInt() and 0xFF) / 255.0)
            assertTrue(abs(apple - iso) / apple < 0.01, "value $v: Apple's boost $apple, ISO's $iso")
        }
    }

    @Test
    fun imageIosToneMapXmpIsTheIsoMetadata() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:HDRToneMap="http://ns.apple.com/HDRToneMap/1.0/">
            <HDRToneMap:Version>1</HDRToneMap:Version>
            <HDRToneMap:BaseHeadroom>0.000000</HDRToneMap:BaseHeadroom>
            <HDRToneMap:AlternateHeadroom>2.000000</HDRToneMap:AlternateHeadroom>
            <HDRToneMap:ChannelMetadata><rdf:Seq><rdf:li rdf:parseType="Resource">
            <HDRToneMap:GainMapMin>0.000000</HDRToneMap:GainMapMin>
            <HDRToneMap:GainMapMax>2.000000</HDRToneMap:GainMapMax>
            <HDRToneMap:Gamma>1.000000</HDRToneMap:Gamma>
            <HDRToneMap:BaseOffset>0.000100</HDRToneMap:BaseOffset>
            <HDRToneMap:AlternateOffset>0.000100</HDRToneMap:AlternateOffset>
            </rdf:li></rdf:Seq></HDRToneMap:ChannelMetadata>
            <HDRToneMap:BaseColorIsWorkingColor>True</HDRToneMap:BaseColorIsWorkingColor>
            </rdf:Description></rdf:RDF></x:xmpmeta>"""
        val metadata = assertNotNull(AppleToneMapXmp.decode(xmp))
        assertEquals(listOf(2.0), metadata.gainMapMax)
        assertEquals(listOf(0.0001), metadata.offsetSdr)
        assertNear(2.0, metadata.hdrCapacityMax, "alternate headroom")
        assertTrue(metadata.useBaseColorSpace)
        assertNull(AppleToneMapXmp.decode(xmp.replace("2.000000</HDRToneMap:AlternateHeadroom>", "0.000000</HDRToneMap:AlternateHeadroom>")), "no headroom is no HDR")
    }
}
