package id.homebase.api.video.transcoder_v2.internal

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/**
 * Codec discovery: walk [MediaCodecList.REGULAR_CODECS] first (curated +
 * hardware-preferred), then [MediaCodecList.ALL_CODECS] (includes
 * software), deduplicated by codec name and filtered by `excludedNames`
 * for the mid-stream retry path. See SPEC §9.1 / §9.2.
 */
internal object CodecSelection {

    fun selectEncoders(
        mimeType: String,
        excludedNames: Set<String> = emptySet(),
    ): List<MediaCodecInfo> = selectCodecs(mimeType, isEncoder = true, excludedNames)

    fun selectDecoders(
        mimeType: String,
        excludedNames: Set<String> = emptySet(),
    ): List<MediaCodecInfo> = selectCodecs(mimeType, isEncoder = false, excludedNames)

    private fun selectCodecs(
        mimeType: String,
        isEncoder: Boolean,
        excludedNames: Set<String>,
    ): List<MediaCodecInfo> {
        val out = ArrayList<MediaCodecInfo>()
        val seen = HashSet<String>()

        val regular = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (info in regular) {
            if (info.isEncoder != isEncoder) continue
            if (info.name in excludedNames) continue
            for (type in info.supportedTypes) {
                if (type.equals(mimeType, ignoreCase = true) && seen.add(info.name)) {
                    out.add(info)
                    break
                }
            }
        }

        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        for (info in all) {
            if (info.isEncoder != isEncoder) continue
            if (info.name in excludedNames) continue
            for (type in info.supportedTypes) {
                if (type.equals(mimeType, ignoreCase = true) && seen.add(info.name)) {
                    out.add(info)
                    break
                }
            }
        }

        return out
    }
}
