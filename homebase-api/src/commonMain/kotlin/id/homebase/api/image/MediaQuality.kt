package id.homebase.api.image

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How an outgoing photo or video is prepared before upload.
 *
 * [ORIGINAL] is a byte-for-byte contract for photos: the picked file's bytes reach the wire
 * unmodified, bar the metadata strip in apps that strip (UPLOADS.md 3.4). Videos are transcoded at 1080p,
 * except in Photos, which keeps the file with a copy to play (UPLOADS.md 3.6).
 */
@Serializable
enum class MediaQuality(val code: String) {
    // Wire names match `code` so a descriptor written by any client reads the same.
    @SerialName("standard")
    STANDARD("standard"),

    @SerialName("hd")
    HD("hd"),

    // "high" because every "high" photo already on the wire and in saved settings is an exact file.
    @SerialName("high")
    ORIGINAL("high");

    companion object {
        fun fromCodeOrNull(code: String?): MediaQuality? = entries.find { it.code == code }
    }
}

/** An app's two send settings: photos and videos are chosen apart (UPLOADS.md section 5). */
data class SendQuality(val photo: MediaQuality, val video: MediaQuality) {
    companion object {
        val Standard = SendQuality(MediaQuality.STANDARD, MediaQuality.STANDARD)

        /** Outside Photos an Original video is HD's 1080p, so a video setting offers only these. */
        val VideoChoices: List<MediaQuality> = listOf(MediaQuality.STANDARD, MediaQuality.HD)
    }
}
