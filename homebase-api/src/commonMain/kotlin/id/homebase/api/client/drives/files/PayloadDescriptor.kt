package id.homebase.api.client.drives.files

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class PayloadDescriptor(
    val key: String,
    val contentType: String? = null,
    val thumbnails: List<ThumbnailDescriptor>? = null,
    val iv: String? = null,
    val bytesWritten: Long? = null,
    val lastModified: Long? = null,
    val descriptorContent: String? = null,
    val previewThumbnail: ThumbnailDescriptor? = null,
    val uid: Long? = null
    // Add fields as needed
) {
    fun keyEquals(otherKey: String): Boolean {
        return key.equals(otherKey, ignoreCase = true)
    }

    fun descriptorInfo(): DescriptorContent {
        return when {
            descriptorContent == null -> {
                DescriptorContent.Empty
            }

            contentType?.startsWith("audio/") == true -> {
                try {
                    val audioDescriptor =
                        OdinSystemSerializer.deserialize<DescriptorContent.AudioFile>(
                            descriptorContent
                        )
                    DescriptorContent.AudioFile(
                        name = audioDescriptor.name,
                        lengthSeconds = audioDescriptor.lengthSeconds
                    )
                } catch (e: Exception) {
                    Logger.w("PayloadFile.descriptorInfo", e)
                    DescriptorContent.Empty
                }

            }

            contentType == "text/markdown" -> {
                DescriptorContent.NoteFile(preview = descriptorContent)
            }

            contentType?.startsWith("video/") == true -> {
                try {
                    val meta = OdinSystemSerializer.deserialize<VideoMetadata>(descriptorContent)
                    DescriptorContent.VideoFile(
                        durationMs = meta.duration.toLong().takeIf { it > 0 },
                        isSegmented = meta.isSegmented,
                        codec = meta.codec.takeIf { it.isNotBlank() },
                        widthPx = meta.widthPx,
                        heightPx = meta.heightPx,
                        bitDepth = meta.bitDepth,
                        isHdr = meta.isHdr,
                        videoBitrateBps = meta.videoBitrateBps,
                        fileSizeBytes = meta.fileSize,
                        mimeType = meta.mimeType,
                    )
                } catch (e: Exception) {
                    Logger.w("PayloadFile.descriptorInfo", e)
                    DescriptorContent.Empty
                }
            }

            contentType?.startsWith("image/") == true -> {
                // Image payloads historically wrote descriptorContent = "" (no info).
                // We now optionally store a tiny {"isSticker":true} object here. Blank /
                // legacy / malformed all fall back to a non-sticker ImageFile so an older
                // or corrupt descriptor never throws at render and never strips a photo's
                // backdrop (opaque is the safe default). Mirrors the audio/video
                // parse-failure contract above.
                //
                // Single-payload link-preview (chat_links) and location-preview (chat_loc)
                // bubbles ALSO carry an image/* contentType but store a JSON *array* in
                // descriptorContent — not an {"isSticker":...} object. Only attempt the
                // ImageFile deserialize when the content actually looks like a JSON object,
                // so the expected blank/array case is silent (no redundant parse, no warning
                // log on the recomposition hot path) while genuine "looks like an object but
                // failed to parse" corruption is still logged.
                if (descriptorContent.trimStart().startsWith("{")) {
                    try {
                        OdinSystemSerializer.deserialize<DescriptorContent.ImageFile>(
                            descriptorContent
                        )
                    } catch (e: Exception) {
                        Logger.w("PayloadFile.descriptorInfo", e)
                        DescriptorContent.ImageFile(isSticker = false)
                    }
                } else {
                    DescriptorContent.ImageFile(isSticker = false)
                }
            }

            else -> DescriptorContent.File(name = descriptorContent)
        }
    }

    fun filename(): String? {
        return when(val info = descriptorInfo()) {
            is DescriptorContent.AudioFile -> info.name
            DescriptorContent.Empty -> null
            is DescriptorContent.File -> info.name
            is DescriptorContent.ImageFile -> null
            is DescriptorContent.NoteFile -> null
            is DescriptorContent.VideoFile -> null
        }
    }
}

sealed interface DescriptorContent {
    data object Empty : DescriptorContent
    data class File(val name: String) : DescriptorContent
    data class NoteFile(val preview: String?) : DescriptorContent
    @Serializable
    data class AudioFile(val name: String?, val lengthSeconds: Int) : DescriptorContent

    /**
     * Per-image descriptor. Rides on the payload's [PayloadDescriptor.descriptorContent]
     * slot (which image payloads otherwise leave blank). [isSticker] marks a transparent
     * cut-out image that should render without the opaque bubble backdrop/clip. Additive
     * and backward-compatible: a blank or legacy descriptor parses to `ImageFile(false)`.
     */
    @Serializable
    data class ImageFile(val isSticker: Boolean = false) : DescriptorContent

    /** Surfaces the bits of a video's [VideoMetadata] that the UI cares about. */
    data class VideoFile(
        val durationMs: Long?,
        val isSegmented: Boolean,
        val codec: String? = null,
        val widthPx: Int = 0,
        val heightPx: Int = 0,
        val bitDepth: Int = 0,
        val isHdr: Boolean = false,
        val videoBitrateBps: Long = 0L,
        val fileSizeBytes: Long = 0L,
        val mimeType: String? = null,
    ) : DescriptorContent

    companion object {
        fun descriptorContentFromAudioFile(name: String, lengthSeconds: Int): String {
            return OdinSystemSerializer.serialize(AudioFile(name, lengthSeconds))
        }

        /** Serialize an [ImageFile] descriptor (e.g. `{"isSticker":true}`) for the wire. */
        fun descriptorContentFromImage(isSticker: Boolean): String {
            return OdinSystemSerializer.serialize(ImageFile(isSticker))
        }
    }
}