package id.homebase.chat.albumshare

import id.homebase.api.util.codePointCount
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * "This album is shared with you": sent by the owner's Photos to each person a share reaches. The photos stay on the
 * owner's drive; the sender is the owner, from the message envelope.
 */
@Serializable
data class AlbumShareDescriptor(
    val albumId: String,
    /** The album's name when it was shared. */
    val title: String,
    val access: String,
    val schemaVersion: Int = 1,
) {
    val canAdd: Boolean get() = access == ACCESS_ADD

    fun album(): Uuid? = Uuid.parseOrNull(albumId)

    fun summaryLine(): String = title

    fun isValid(): Boolean =
        album() != null &&
            title.isNotBlank() && title.codePointCount() <= MAX_TITLE_CODEPOINTS &&
            (access == ACCESS_VIEW || access == ACCESS_ADD)

    companion object {
        const val ACCESS_VIEW = "view"
        const val ACCESS_ADD = "add"
        // An album's name is at most 100 (PhotoAlbums).
        const val MAX_TITLE_CODEPOINTS = 100
    }
}
