package id.homebase.chat.fileshare

import id.homebase.api.util.codePointCount
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * "These files are shared with you": sent by the sharer's Filesync to each person a share reaches. The files stay on
 * the sharer's drive; [circleId] is the circle the share went to, a person's own or a group's.
 */
@Serializable
data class FileShareDescriptor(
    val circleId: String,
    /** The top folder shared, or the first file when no folder was. */
    val title: String,
    val titleIsFolder: Boolean = false,
    val files: Int,
    val bytes: Long,
    val access: String,
    val group: String? = null,
    val note: String? = null,
    val expiresAt: Long? = null,
    val schemaVersion: Int = 1,
) {
    val canEdit: Boolean get() = access == ACCESS_EDIT

    fun circle(): Uuid? = Uuid.parseOrNull(circleId)

    fun summaryLine(): String = when {
        files == 1 && !titleIsFolder -> title
        files == 1 -> "$title · 1 file"
        else -> "$title · $files files"
    }

    fun isValid(): Boolean =
        circle() != null &&
            title.isNotBlank() && title.codePointCount() <= MAX_TITLE_CODEPOINTS &&
            files >= 0 && bytes >= 0 &&
            (access == ACCESS_VIEW || access == ACCESS_EDIT) &&
            (group == null || group.codePointCount() <= MAX_GROUP_CODEPOINTS) &&
            (note == null || note.codePointCount() <= MAX_NOTE_CODEPOINTS)

    companion object {
        const val ACCESS_VIEW = "view"
        const val ACCESS_EDIT = "edit"
        // Tight, so the descriptor stays well under the header's 7 KB once encrypted.
        const val MAX_TITLE_CODEPOINTS = 80
        const val MAX_GROUP_CODEPOINTS = 80
        const val MAX_NOTE_CODEPOINTS = 280
    }
}
