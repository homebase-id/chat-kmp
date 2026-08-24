package id.homebase.api.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoProbeTagsTest {

    // --- bitDepthFromPixFmt (#959: null when undeterminable, never a fail-open 8) ---

    @Test fun bitDepth_null_pixFmt_isNull() = assertNull(bitDepthFromPixFmt(null))
    @Test fun bitDepth_blank_pixFmt_isNull() = assertNull(bitDepthFromPixFmt("  "))

    @Test fun bitDepth_yuv420p_is8() = assertEquals(8, bitDepthFromPixFmt("yuv420p"))
    @Test fun bitDepth_yuv420p10le_is10() = assertEquals(10, bitDepthFromPixFmt("yuv420p10le"))
    @Test fun bitDepth_p010le_is10() = assertEquals(10, bitDepthFromPixFmt("p010le"))
    @Test fun bitDepth_12bit_is12() = assertEquals(12, bitDepthFromPixFmt("yuv420p12le"))
    @Test fun bitDepth_caseAndSpace_normalized() = assertEquals(10, bitDepthFromPixFmt("  YUV420P10LE "))

    // --- isHdrFromColorTags (null when no tags present at all) ---

    @Test fun hdr_bothAbsent_isNull() = assertNull(isHdrFromColorTags(null, null))
    @Test fun hdr_bothBlank_isNull() = assertNull(isHdrFromColorTags(" ", ""))

    @Test fun hdr_pq_isTrue() = assertEquals(true, isHdrFromColorTags("smpte2084", ""))
    @Test fun hdr_hlg_isTrue() = assertEquals(true, isHdrFromColorTags("arib-std-b67", ""))
    @Test fun hdr_bt2020_primaries_isTrue() = assertEquals(true, isHdrFromColorTags("", "bt2020nc"))
    @Test fun hdr_sdr_bt709_isFalse() = assertEquals(false, isHdrFromColorTags("bt709", "bt709"))
    @Test fun hdr_caseAndSpace_normalized() = assertEquals(true, isHdrFromColorTags(" SMPTE2084 ", null))
}
