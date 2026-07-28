package id.homebase.api.video

/**
 * Shared ffprobe colour-tag parsing, used by every platform's authoritative probe path so the
 * 8-bit/HDR determination is identical across Android (FFprobeKit fallback), JVM (ffprobe binary),
 * and iOS. All functions are fail-closed: they return **null** ("couldn't determine") rather than
 * defaulting to 8-bit/SDR, so an undeterminable source is re-encoded, never passed through (#959).
 */

/**
 * Luma bit depth inferred from an ffprobe `pix_fmt` token, or null when the token is blank/absent
 * (undeterminable). ffmpeg names 10/12-bit formats with a `10`/`12` infix (e.g. `yuv420p10le`,
 * `p010le`); a present token without one is a positive 8-bit result.
 */
internal fun bitDepthFromPixFmt(pixFmt: String?): Int? {
    val f = pixFmt?.trim()?.lowercase()
    return when {
        f.isNullOrBlank() -> null
        "12" in f -> 12
        "10" in f || f.startsWith("p010") -> 10
        else -> 8
    }
}

/**
 * HDR flag from ffprobe colour tags, or null when neither the transfer nor the primaries tag is
 * present (undeterminable). A PQ (`smpte2084`) or HLG (`arib-std-b67`) transfer, or BT.2020
 * primaries, marks HDR.
 */
internal fun isHdrFromColorTags(colorTransfer: String?, colorPrimaries: String?): Boolean? {
    val transfer = colorTransfer?.trim()?.lowercase()
    val primaries = colorPrimaries?.trim()?.lowercase()
    if (transfer.isNullOrBlank() && primaries.isNullOrBlank()) return null
    return transfer == "smpte2084" ||
        transfer == "arib-std-b67" ||
        (primaries?.startsWith("bt2020") == true)
}
