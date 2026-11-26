package id.homebase.chat.fileshare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.chat.widget.ShareCard
import id.homebase.chat.widget.ShareDetailLine
import id.homebase.chat.widget.UnparseableShareCard
import id.homebase.common.util.formatBytes
import id.homebase.core.ui.assets.Filesync
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.util.formatMediumDate
import id.homebase.resources.MR
import id.homebase.resources.chat_file_share_by_many
import id.homebase.resources.chat_file_share_by_one
import id.homebase.resources.chat_file_share_can_edit
import id.homebase.resources.chat_file_share_can_view
import id.homebase.resources.chat_file_share_group
import id.homebase.resources.chat_file_share_unparseable
import id.homebase.resources.chat_file_share_until
import id.homebase.resources.chat_file_share_you_many
import id.homebase.resources.chat_file_share_you_one
import id.homebase.resources.filesync_label
import id.homebase.resources.filesync_open
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * Files shared from [sharer]'s Filesync drive, or from this identity's when [sharer] is null. [onOpen] null leaves the
 * card inert, as in a preview.
 */
@Composable
fun FileShareBubble(
    descriptor: FileShareDescriptor?,
    sharer: String?,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    if (descriptor == null || !descriptor.isValid()) {
        UnparseableShareCard(HomebaseIcons.Filesync, stringResource(MR.string.chat_file_share_unparseable), modifier, footer)
        return
    }
    val size = remember(descriptor.bytes) { formatBytes(descriptor.bytes) }
    val shared = when {
        sharer == null && descriptor.files == 1 -> stringResource(MR.string.chat_file_share_you_one, size)
        sharer == null -> stringResource(MR.string.chat_file_share_you_many, descriptor.files, size)
        descriptor.files == 1 -> stringResource(MR.string.chat_file_share_by_one, sharer, size)
        else -> stringResource(MR.string.chat_file_share_by_many, sharer, descriptor.files, size)
    }
    val access = stringResource(if (descriptor.canEdit) MR.string.chat_file_share_can_edit else MR.string.chat_file_share_can_view)
    val until = remember(descriptor.expiresAt) { descriptor.expiresAt?.let { formatMediumDate(Instant.fromEpochMilliseconds(it)) } }
    val terms = until?.let { stringResource(MR.string.chat_file_share_until, access, it) } ?: access
    ShareCard(
        icon = HomebaseIcons.Filesync,
        iconDescription = stringResource(MR.string.filesync_label),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (descriptor.titleIsFolder) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    descriptor.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        subtitle = shared,
        openLabel = stringResource(MR.string.filesync_open),
        modifier = modifier,
        onOpen = onOpen,
        onLongClick = onLongClick,
        footer = footer,
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            descriptor.group?.let { ShareDetailLine(Icons.Outlined.Group, stringResource(MR.string.chat_file_share_group, it)) }
            ShareDetailLine(if (descriptor.canEdit) Icons.Outlined.Edit else Icons.Outlined.Visibility, terms)
        }
        descriptor.note?.takeIf { it.isNotBlank() }?.let { note ->
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp))
        }
    }
}
