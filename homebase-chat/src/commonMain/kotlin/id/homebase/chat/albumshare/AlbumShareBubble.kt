package id.homebase.chat.albumshare

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.chat.widget.UnparseableTypedChip
import id.homebase.resources.MR
import id.homebase.resources.chat_album_share_by
import id.homebase.resources.chat_album_share_can_add
import id.homebase.resources.chat_album_share_open
import id.homebase.resources.chat_album_share_unparseable
import id.homebase.resources.chat_album_share_you
import id.homebase.resources.chat_file_share_can_view
import id.homebase.resources.chat_message_options
import org.jetbrains.compose.resources.stringResource

/** An album shared from [sharer]'s Photos, or from this identity's when [sharer] is null. [onOpen] null leaves the card inert, as in a preview. */
@OptIn(ExperimentalFoundationApi::class)
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
        UnparseableTypedChip(
            Icons.Outlined.PhotoAlbum,
            stringResource(MR.string.chat_album_share_unparseable),
            modifier,
            footer = footer?.let { f ->
                { Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) { f() } }
            },
        )
        return
    }
    val shared = if (sharer == null) stringResource(MR.string.chat_album_share_you) else stringResource(MR.string.chat_album_share_by, sharer)
    val access = stringResource(if (descriptor.canAdd) MR.string.chat_album_share_can_add else MR.string.chat_file_share_can_view)
    val openLabel = stringResource(MR.string.chat_album_share_open)
    Surface(
        modifier = modifier.widthIn(min = 240.dp, max = 320.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .semantics(mergeDescendants = true) {}
                .let {
                    if (onOpen != null) {
                        it.combinedClickable(
                            onClick = onOpen,
                            onClickLabel = openLabel,
                            onLongClick = onLongClick,
                            onLongClickLabel = stringResource(MR.string.chat_message_options),
                        )
                    } else it
                },
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Icon(Icons.Outlined.PhotoAlbum, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(descriptor.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(shared, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (descriptor.canAdd) Icons.Outlined.AddPhotoAlternate else Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(access, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onOpen != null) TextButton(onClick = onOpen) { Text(openLabel) } else Spacer(Modifier.size(12.dp))
                Spacer(Modifier.weight(1f))
                footer?.invoke()
            }
        }
    }
}
