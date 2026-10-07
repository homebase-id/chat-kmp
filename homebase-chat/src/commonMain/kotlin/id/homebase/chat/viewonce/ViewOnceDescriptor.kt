package id.homebase.chat.viewonce

import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.serialization.Serializable

/** Wire format of a view-once message (dataType 216). */
@Serializable
data class ViewOnceDescriptor(
    val kind: String,
    val schemaVersion: Int = 1,
    // Only the viewer reads it; the label, preview, notification and reply quote never do.
    val caption: String? = null,
) {
    fun isValid(): Boolean = kind == KIND_IMAGE || kind == KIND_VIDEO

    fun summaryLine(): String = if (kind == KIND_VIDEO) "Video" else "Photo"

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
        const val MAX_CAPTION_CODEPOINTS = 1000
    }
}

/** What a view-once tombstone keeps of its descriptor: the kind, so a spent item still reads Photo or Video; never the caption. */
fun viewOnceTombstoneContent(content: String?): String {
    if (content.isNullOrBlank()) return ""
    return try {
        val descriptor = OdinSystemSerializer.deserialize<ViewOnceDescriptor>(content)
        if (descriptor.isValid()) OdinSystemSerializer.serialize(descriptor.copy(caption = null)) else ""
    } catch (_: Exception) {
        ""
    }
}
