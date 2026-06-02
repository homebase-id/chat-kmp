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

            else -> DescriptorContent.File(name = descriptorContent)
        }
    }

    fun filename(): String? {
        return when(val info = descriptorInfo()) {
            is DescriptorContent.AudioFile -> info.name
            DescriptorContent.Empty -> null
            is DescriptorContent.File -> info.name
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
    }
}