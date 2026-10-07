package id.homebase.chat.viewonce

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
