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
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.feed_audience_public
import id.homebase.resources.feed_channel_locked
import id.homebase.resources.feed_post_delete_confirm
import id.homebase.resources.feed_post_detail_delete
import id.homebase.resources.feed_post_detail_more_actions
import id.homebase.resources.feed_post_edit
import id.homebase.resources.feed_post_report
import id.homebase.resources.feed_post_to_channel
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * Top row of a feed post card: author avatar + display name, an optional
 * "to <channel>" line, and a relative timestamp. Purely presentational — the
 * caller resolves [displayName] and [channelName]; this widget never does a
 * lookup.
 *
 * @param authorOdinId identity used to fetch the avatar (caller passes
 *   `originalAuthor ?: senderOdinId`).
 * @param displayName already-resolved name to render.
 * @param channelName optional channel the post was published to.
 * @param timestampMs epoch-ms authored/publish time (the post's `userDate`), rendered via
 *   [formatTimestamp]. NOT the drive file's `created`, which on a followed/public post is the
 *   aggregation time onto the local feed drive, not when the author posted.
 * @param onAuthorClick invoked when the avatar or name is tapped.
 * @param isOwnPost true when the post was authored by the current user — selects the overflow
 *   menu's actions (Edit/Delete vs Report).
 * @param onEditPost / onDeletePost / onReportPost overflow-menu handlers. Null handlers are
 *   omitted; when none apply the trailing `…` button isn't shown at all.
 */
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
    onEditPost: (() -> Unit)? = null,
    onDeletePost: (() -> Unit)? = null,
    onReportPost: (() -> Unit)? = null,
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
            // Web `PostMeta` (Meta.tsx) renders the date first, then a separated audience link
            // carrying a glyph — so timestamp leads, audience follows. A public post shows a globe
            // + "Public"; a restricted channel shows a lock + its name; an unknown/still-loading
            // channel shows nothing until its definition arrives.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTimestamp(Instant.fromEpochMilliseconds(timestampMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                val channelLabel = channelName?.takeIf { it.isNotBlank() }
                if (isPublic || channelLabel != null) {
                    // A thin 12dp hairline separates timestamp from audience (not a middot),
                    // matching the M3 redesign spec.
                    VerticalDivider(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .height(12.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Icon(
                        imageVector = if (isPublic) Icons.Outlined.Public else Icons.Outlined.Lock,
                        contentDescription = if (isPublic) {
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
                        text = if (isPublic) {
                            stringResource(MR.string.feed_audience_public)
                        } else {
                            stringResource(MR.string.feed_post_to_channel, channelLabel ?: "")
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
        )
    }
}

/**
 * Trailing `…` overflow for a post. Own post → Edit + Delete; someone else's → Report (web
 * `OwnerActions` / `ExternalActions` parity). Renders nothing when no handler applies, so a card
 * with no actions (e.g. inside the detail screen, which has its own top-bar menu) shows no button.
 * Delete is guarded by a confirm dialog — it's a one-tap-from-the-list destructive action.
 */
@Composable
private fun PostOverflowMenu(
    isOwnPost: Boolean,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onReport: (() -> Unit)?,
) {
    val hasOwnerActions = isOwnPost && (onEdit != null || onDelete != null)
    val hasViewerActions = !isOwnPost && onReport != null
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
