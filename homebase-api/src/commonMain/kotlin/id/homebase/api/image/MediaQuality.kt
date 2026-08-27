package id.homebase.api.image

/**
 * How hard an outgoing photo or video is compressed before upload.
 *
 * [HIGH] is a byte-for-byte contract: the picked file's bytes reach the wire unmodified
 * (bar the EXIF scrub every image already gets).
 */
enum class MediaQuality(val code: String) {
    STANDARD("standard"),
    HIGH("high");

    companion object {
        fun fromCode(code: String?): MediaQuality = entries.find { it.code == code } ?: STANDARD
    }
}
