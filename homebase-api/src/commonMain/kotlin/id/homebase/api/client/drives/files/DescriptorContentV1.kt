package id.homebase.api.client.drives.files

import co.touchlab.kermit.Logger
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesGcm
import id.homebase.api.crypto.Base64Encoder
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.image.MediaQuality
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The wire format of a payload's descriptor content, as UPLOADS.md section 1 specifies it. */
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
        ;

        companion object {
            fun of(quality: MediaQuality): Quality = entries.first { it.mediaQuality == quality }
        }
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

    /** Today's video model, so the player reads both formats one way. [playlist] is the `p` payload's text. */
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

        /** Null for every older form: none of them is a JSON object carrying `v` and `k`. */
        fun parseOrNull(content: String): DescriptorContentV1? {
            if (!content.trimStart().startsWith("{") || !content.contains("\"k\"")) return null
            return try {
                OdinSystemSerializer.deserialize<DescriptorContentV1>(content).takeIf { it.version == VERSION }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** AES-GCM for the descriptor content of an encrypted HomebaseFile (UPLOADS.md section 2). */
object DescriptorContentSeal {
    private const val FORMAT: Byte = 1
    private const val IV_LENGTH = 12
    private const val WRAPPER_KEY = "e"
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun seal(plaintext: String, key: SecureByteArray): String {
        val iv = ByteArrayUtil.getRndByteArray(IV_LENGTH)
        val sealed = byteArrayOf(FORMAT) + iv + AesGcm.encrypt(plaintext.encodeToByteArray(), key, iv)
        return OdinSystemSerializer.serialize(mapOf(WRAPPER_KEY to Base64Encoder.encode(sealed)))
    }

    /** The sealed bytes when [content] is a JSON object holding only `e`, else null. */
    private fun sealedBytes(content: String): ByteArray? {
        if (!content.trimStart().startsWith("{")) return null
        val wrapper = try {
            json.parseToJsonElement(content) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        if (wrapper.keys != setOf(WRAPPER_KEY)) return null
        return try {
            Base64Encoder.decode(wrapper.getValue(WRAPPER_KEY).jsonPrimitive.content)
        } catch (e: Exception) {
            null
        }
    }

    fun isSealed(content: String): Boolean = sealedBytes(content) != null

    /** The plaintext, or null when it isn't sealed or doesn't open (a wrong key or a changed byte). */
    suspend fun openOrNull(content: String, key: SecureByteArray): String? {
        val sealed = sealedBytes(content) ?: return null
        if (sealed.size <= 1 + IV_LENGTH || sealed[0] != FORMAT) return null
        return try {
            AesGcm.decrypt(sealed.copyOfRange(1 + IV_LENGTH, sealed.size), key, sealed.copyOfRange(1, 1 + IV_LENGTH)).decodeToString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = "DescriptorContentSeal") { "descriptor content doesn't open: ${e.message}" }
            null
        }
    }
}
