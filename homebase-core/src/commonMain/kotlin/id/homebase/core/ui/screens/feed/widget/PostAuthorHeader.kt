package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.feed.services.PostAudience
import id.homebase.core.feed.services.isRestricted
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.feed_audience_auto_connected
import id.homebase.resources.feed_audience_authenticated
import id.homebase.resources.feed_audience_circles
import id.homebase.resources.feed_audience_connections
import id.homebase.resources.feed_audience_owner
import id.homebase.resources.feed_audience_public
import id.homebase.resources.feed_channel_locked
import id.homebase.resources.feed_post_delete_confirm
import id.homebase.resources.feed_post_detail_delete
import id.homebase.resources.feed_post_detail_more_actions
import id.homebase.resources.feed_post_edit
import id.homebase.resources.feed_post_block
import id.homebase.resources.feed_post_report
import id.homebase.resources.feed_post_to_channel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

// [timestampMs] is the post's userDate, NOT the drive file's `created` — on a followed post that is the
// aggregation time onto the local feed drive, not when the author posted.
@Composable
fun PostAuthorHeader(
    authorOdinId: OdinId,
    displayName: String,
    channelName: String?,
    timestampMs: Long,
    onAuthorClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPublic: Boolean = false,
    isOwnPost: Boolean = false,
    audience: PostAudience? = null,
    onEditPost: (() -> Unit)? = null,
    onDeletePost: (() -> Unit)? = null,
    onReportPost: (() -> Unit)? = null,
    onBlockAuthor: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PublicAvatar(
            odinId = authorOdinId,
            initials = displayName.initials(),
            options = AvatarOptions(size = 40.dp, onClick = onAuthorClick),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onAuthorClick),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Timestamp leads, audience follows (web Meta.tsx). An unknown/still-loading channel shows nothing
            // until its definition arrives.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTimestamp(Instant.fromEpochMilliseconds(timestampMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                // A restricted audience wins over the channel name: "who can see this" is the more useful fact.
                val audienceLabel = audience?.takeIf { it.isRestricted }?.labelResource()
                val channelLabel = channelName?.takeIf { it.isNotBlank() }
                if (isPublic || channelLabel != null || audienceLabel != null) {
                    VerticalDivider(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .height(12.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    val open = isPublic && audienceLabel == null
                    Icon(
                        imageVector = if (open) Icons.Outlined.Public else Icons.Outlined.Lock,
                        contentDescription = if (open) {
                            stringResource(MR.string.feed_audience_public)
                        } else {
                            stringResource(MR.string.feed_channel_locked)
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 3.dp)
                            .size(12.dp),
                    )
                    Text(
                        text = when {
                            audienceLabel != null -> stringResource(audienceLabel)
                            isPublic -> stringResource(MR.string.feed_audience_public)
                            else -> stringResource(
                                MR.string.feed_post_to_channel,
                                channelLabel ?: "",
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        PostOverflowMenu(
            isOwnPost = isOwnPost,
            onEdit = onEditPost,
            onDelete = onDeletePost,
            onReport = onReportPost,
            onBlock = onBlockAuthor,
        )
    }
}

private fun PostAudience.labelResource(): StringResource = when (this) {
    PostAudience.Public -> MR.string.feed_audience_public
    PostAudience.Authenticated -> MR.string.feed_audience_authenticated
    PostAudience.AutoConnected -> MR.string.feed_audience_auto_connected
    PostAudience.Connections -> MR.string.feed_audience_connections
    PostAudience.Circles -> MR.string.feed_audience_circles
    PostAudience.Owner -> MR.string.feed_audience_owner
}

// Renders nothing when no handler applies, so a card with no actions shows no button. Delete is guarded by a
// confirm dialog — it's a one-tap-from-the-list destructive action.
@Composable
private fun PostOverflowMenu(
    isOwnPost: Boolean,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onReport: (() -> Unit)?,
    onBlock: (() -> Unit)?,
) {
    val hasOwnerActions = isOwnPost && (onEdit != null || onDelete != null)
    val hasViewerActions = !isOwnPost && (onReport != null || onBlock != null)
    if (!hasOwnerActions && !hasViewerActions) return

    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(MR.string.feed_post_detail_more_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (isOwnPost) {
                onEdit?.let { edit ->
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.string.feed_post_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            expanded = false
                            edit()
                        },
                    )
                }
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.string.feed_post_detail_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            expanded = false
                            confirmDelete = true
                        },
                    )
                }
            } else {
                onReport?.let { report ->
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.string.feed_post_report)) },
                        leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
                        onClick = {
                            expanded = false
                            report()
                        },
                    )
                }
                onBlock?.let { block ->
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.string.feed_post_block)) },
                        leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                        onClick = {
                            expanded = false
                            block()
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(MR.string.feed_post_detail_delete)) },
            text = { Text(stringResource(MR.string.feed_post_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(MR.string.feed_post_detail_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        )
    }
}
