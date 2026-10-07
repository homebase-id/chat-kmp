package id.homebase.chat.viewonce

import kotlinx.serialization.Serializable

/** Wire format of a view-once message (dataType 216). */
@Serializable
data class ViewOnceDescriptor(
    val kind: String,
    val schemaVersion: Int = 1,
) {
    fun isValid(): Boolean = kind == KIND_IMAGE || kind == KIND_VIDEO

    fun summaryLine(): String = if (kind == KIND_VIDEO) "View-once video" else "View-once photo"

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
    }
}
