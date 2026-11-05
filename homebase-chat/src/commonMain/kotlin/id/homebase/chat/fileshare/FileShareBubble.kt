package id.homebase.chat.fileshare

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
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.common.util.formatBytes
import id.homebase.core.ui.assets.Filesync
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.util.formatMediumDate
import id.homebase.resources.MR
import id.homebase.resources.chat_file_share_actions
import id.homebase.resources.chat_file_share_by_many
import id.homebase.resources.chat_file_share_by_one
import id.homebase.resources.chat_file_share_can_edit
import id.homebase.resources.chat_file_share_can_view
import id.homebase.resources.chat_file_share_group
import id.homebase.resources.chat_file_share_open
import id.homebase.resources.chat_file_share_unparseable
import id.homebase.resources.chat_file_share_until
import id.homebase.resources.chat_file_share_you_many
import id.homebase.resources.chat_file_share_you_one
import id.homebase.resources.filesync_label
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * Files shared from [sharer]'s Filesync drive, or from this identity's when [sharer] is null. [onOpen] null leaves the
 * card inert, as in a preview.
 */
@OptIn(ExperimentalFoundationApi::class)
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
        UnparseableFileShareBubble(modifier, footer)
        return
    }
    val size = formatBytes(descriptor.bytes)
    val shared = when {
        sharer == null && descriptor.files == 1 -> stringResource(MR.string.chat_file_share_you_one, size)
        sharer == null -> stringResource(MR.string.chat_file_share_you_many, descriptor.files, size)
        descriptor.files == 1 -> stringResource(MR.string.chat_file_share_by_one, sharer, size)
        else -> stringResource(MR.string.chat_file_share_by_many, sharer, descriptor.files, size)
    }
    val access = stringResource(if (descriptor.canEdit) MR.string.chat_file_share_can_edit else MR.string.chat_file_share_can_view)
    val terms = descriptor.expiresAt?.let { stringResource(MR.string.chat_file_share_until, access, formatMediumDate(Instant.fromEpochMilliseconds(it))) } ?: access
    val openLabel = stringResource(MR.string.chat_file_share_open)
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
                            onLongClickLabel = stringResource(MR.string.chat_file_share_actions),
                        )
                    } else it
                },
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Icon(
                        HomebaseIcons.Filesync,
                        contentDescription = stringResource(MR.string.filesync_label),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    Text(shared, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                descriptor.group?.let { DetailLine(Icons.Outlined.Group, stringResource(MR.string.chat_file_share_group, it)) }
                DetailLine(if (descriptor.canEdit) Icons.Outlined.Edit else Icons.Outlined.Visibility, terms)
            }
            descriptor.note?.takeIf { it.isNotBlank() }?.let { note ->
                HorizontalDivider(modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp))
            }
            Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onOpen != null) TextButton(onClick = onOpen) { Text(openLabel) } else Spacer(Modifier.size(12.dp))
                Spacer(Modifier.weight(1f))
                footer?.invoke()
            }
        }
    }
}

@Composable
private fun DetailLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnparseableFileShareBubble(modifier: Modifier, footer: (@Composable () -> Unit)?) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = if (footer != null) 2.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HomebaseIcons.Filesync, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(MR.string.chat_file_share_unparseable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            footer?.let { Row(modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 4.dp)) { it() } }
        }
    }
}
