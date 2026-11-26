package id.homebase.chat.albumshare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.chat.widget.ShareCard
import id.homebase.chat.widget.ShareDetailLine
import id.homebase.chat.widget.UnparseableShareCard
import id.homebase.resources.MR
import id.homebase.resources.chat_album_share_by
import id.homebase.resources.chat_album_share_can_add
import id.homebase.resources.chat_album_share_open
import id.homebase.resources.chat_album_share_unparseable
import id.homebase.resources.chat_album_share_you
import id.homebase.resources.chat_file_share_can_view
import id.homebase.resources.photobase_label
import org.jetbrains.compose.resources.stringResource

/** An album shared from [sharer]'s Photos, or from this identity's when [sharer] is null. [onOpen] null leaves the card inert, as in a preview. */
@Composable
fun AlbumShareBubble(
    descriptor: AlbumShareDescriptor?,
    sharer: String?,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    if (descriptor == null || !descriptor.isValid()) {
        UnparseableShareCard(Icons.Outlined.PhotoAlbum, stringResource(MR.string.chat_album_share_unparseable), modifier, footer)
        return
    }
    val shared = if (sharer == null) stringResource(MR.string.chat_album_share_you) else stringResource(MR.string.chat_album_share_by, sharer)
    val access = stringResource(if (descriptor.canAdd) MR.string.chat_album_share_can_add else MR.string.chat_file_share_can_view)
    ShareCard(
        icon = Icons.Outlined.PhotoAlbum,
        iconDescription = stringResource(MR.string.photobase_label),
        title = { Text(descriptor.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        subtitle = shared,
        openLabel = stringResource(MR.string.chat_album_share_open),
        modifier = modifier,
        onOpen = onOpen,
        onLongClick = onLongClick,
        footer = footer,
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp)) {
            ShareDetailLine(if (descriptor.canAdd) Icons.Outlined.AddPhotoAlternate else Icons.Outlined.Visibility, access)
        }
    }
}
