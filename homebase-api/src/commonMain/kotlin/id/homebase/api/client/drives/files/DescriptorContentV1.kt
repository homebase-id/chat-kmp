package id.homebase.api.client.drives.files

import co.touchlab.kermit.Logger
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.Base64Encoder
import id.homebase.api.crypto.ZkLock
import id.homebase.api.crypto.ZkOpenException
import id.homebase.api.image.MediaQuality
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// UPLOADS.md section 1.
@Serializable
data class DescriptorContentV1(
    @SerialName("v") val version: Int = VERSION,
    @SerialName("k") val kind: Kind,
    @SerialName("n") val name: String? = null,
    @SerialName("o") val original: Int? = null,
    @SerialName("w") val pixelWidth: Int? = null,
    @SerialName("h") val pixelHeight: Int? = null,
    @SerialName("q") val quality: Quality? = null,
    @SerialName("l") val lengthSeconds: Int? = null,
    @SerialName("vd") val video: Video? = null,
    @SerialName("x") val appContent: String? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("i") IMAGE,
        @SerialName("v") VIDEO,
        @SerialName("a") AUDIO,
        @SerialName("f") FILE,
        @SerialName("s") STICKER,
        @SerialName("x") APP,
    }

    @Serializable
    enum class Quality(val mediaQuality: MediaQuality) {
        @SerialName("sd") SD(MediaQuality.STANDARD),
        @SerialName("hd") HD(MediaQuality.HD),
        @SerialName("org") ORG(MediaQuality.ORIGINAL),
    }

    @Serializable
    data class Video(
        @SerialName("d") val durationMs: Long,
        @SerialName("c") val codec: String,
        @SerialName("seg") val segmented: Int? = null,
        @SerialName("p") val playlistKey: String? = null,
        @SerialName("sz") val plainBytes: Long? = null,
        @SerialName("bd") val bitDepth: Int? = null,
        @SerialName("hdr") val hdr: Int? = null,
        @SerialName("br") val bitrateBps: Long? = null,
    )

    fun encode(): String = OdinSystemSerializer.serialize(this)

    fun toVideoMetadata(payloadKey: String, playlist: String?): VideoMetadata? {
        val video = video ?: return null
        val segmented = video.segmented == 1
        return VideoMetadata(
            mimeType = if (segmented) HLS_PLAYLIST_CONTENT_TYPE else "video/mp4",
            isSegmented = segmented,
            fileSize = video.plainBytes ?: 0L,
            duration = video.durationMs.toFloat(),
            key = payloadKey,
            codec = video.codec,
            hlsPlaylist = playlist,
            widthPx = pixelWidth ?: 0,
            heightPx = pixelHeight ?: 0,
            bitDepth = video.bitDepth ?: 0,
            isHdr = video.hdr == 1,
            videoBitrateBps = video.bitrateBps ?: 0L,
        )
    }

    companion object {
        const val VERSION = 1

        // No older form is a JSON object with a "k" key, so they all stop at the cheap checks.
        fun parseOrNull(content: String): DescriptorContentV1? {
            if (!content.startsWith("{") || !content.contains("\"k\"")) return null
            return runCatching { OdinSystemSerializer.deserialize<DescriptorContentV1>(content) }.getOrNull()?.takeIf { it.version == VERSION }
        }
    }
}

/** The descriptor content of an encrypted HomebaseFile: `{"e": base64(0x01 ‖ zkLock)}` (UPLOADS.md section 2). */
object DescriptorContentSeal {
    private const val FORMAT: Byte = 1
    private val strict = Json

    @Serializable
    private class Sealed(val e: String)

    suspend fun seal(plaintext: String, key: SecureByteArray): String =
        strict.encodeToString(Sealed.serializer(), Sealed(Base64Encoder.encode(byteArrayOf(FORMAT) + ZkLock.lock(plaintext.encodeToByteArray(), key.unsafeBytes))))

    /** [content] itself when it isn't sealed; its plaintext when it is; null when it doesn't open. */
    suspend fun open(content: String, key: SecureByteArray): String? {
        if (!content.startsWith("{\"e\":")) return content
        val sealed = runCatching { Base64Encoder.decode(strict.decodeFromString(Sealed.serializer(), content).e) }.getOrNull()
            ?: return content
        if (sealed.firstOrNull() != FORMAT) return null
        return try {
            ZkLock.open(sealed.copyOfRange(1, sealed.size), key.unsafeBytes).decodeToString()
        } catch (e: ZkOpenException) {
            Logger.w(tag = "DescriptorContentSeal") { "descriptor content doesn't open: ${e.message}" }
            null
        }
    }
}
