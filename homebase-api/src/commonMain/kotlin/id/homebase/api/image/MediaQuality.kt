package id.homebase.api.image

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How an outgoing photo or video is prepared before upload.
 *
 * [ORIGINAL] is a byte-for-byte contract: the picked file's bytes reach the wire unmodified
 * (bar the EXIF scrub every image already gets).
 */
@Serializable
enum class MediaQuality(val code: String) {
    // Wire names match `code` so a descriptor written by any client reads the same.
    @SerialName("standard")
    STANDARD("standard"),

    @SerialName("hd")
    HD("hd"),

    // "high" because every "high" already on the wire and in saved settings means an exact file.
    @SerialName("high")
    ORIGINAL("high");

    companion object {
        fun fromCode(code: String?): MediaQuality = entries.find { it.code == code } ?: STANDARD
    }
}
