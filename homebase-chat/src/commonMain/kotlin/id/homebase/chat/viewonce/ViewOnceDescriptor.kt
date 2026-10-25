package id.homebase.chat.viewonce

import id.homebase.resources.MR
import id.homebase.resources.chat_view_once_photo
import id.homebase.resources.chat_view_once_unparseable
import id.homebase.resources.chat_view_once_video
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource

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

// A consumed tombstone may have lost its kind; the generic word still says what it was.
fun ViewOnceDescriptor?.kindLabel(): StringResource = when (this?.kind) {
    ViewOnceDescriptor.KIND_VIDEO -> MR.string.chat_view_once_video
    ViewOnceDescriptor.KIND_IMAGE -> MR.string.chat_view_once_photo
    else -> MR.string.chat_view_once_unparseable
}
