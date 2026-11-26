package id.homebase.photobase.sharing

import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.albumshare.AlbumShareDescriptor
import id.homebase.chat.services.content.MessageContent
import kotlin.uuid.Uuid

/** The chat message telling someone about album [albumId], shared with them with [access]. */
fun albumShareInvite(albumId: Uuid, albumName: String, access: PhotoAlbumAccess): MessageContent.AlbumShare = MessageContent.AlbumShare(
    AlbumShareDescriptor(
        albumId = albumId.toString(),
        title = albumName.truncateToCodePoints(AlbumShareDescriptor.MAX_TITLE_CODEPOINTS),
        access = if (access == PhotoAlbumAccess.Add) AlbumShareDescriptor.ACCESS_ADD else AlbumShareDescriptor.ACCESS_VIEW,
    )
)
