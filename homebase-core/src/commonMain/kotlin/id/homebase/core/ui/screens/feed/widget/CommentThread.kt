package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.chat.widget.ChatMarkdown
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.feed.services.PostCommentItem
import id.homebase.core.ui.screens.moments.widget.MomentMediaGallery
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.delete
import id.homebase.resources.edit
import id.homebase.resources.feed_comment_like
import id.homebase.resources.feed_comment_reply
import id.homebase.resources.feed_post_detail_more_actions
import id.homebase.resources.save
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Default emoji applied by a comment's like affordance. */
private const val COMMENT_LIKE_EMOJI = "❤️"

/**
 * Renders [comments] as one-level threads: each top-level comment (`replyToId == null`)
 * is followed by its replies (`replyToId == comment.id`), indented. A reply button is
 * only offered on top-level comments — replies cannot themselves be replied to.
 *
 * Purely presentational: name resolution is delegated to [displayNameFor]; every action
 * is a callback. [isMine] decides whether the edit/delete affordances show for a row.
 *
 * @param onToggleCommentReaction toggles the given emoji on a comment.
 * @param onReply starts a reply to a top-level comment.
 * @param onEdit commits an edited body for a comment. The widget owns the inline edit
 *   field (prefilled with the current body) and only invokes this on Save.
 */
@Composable
fun CommentThread(
    comments: List<PostCommentItem>,
    displayNameFor: (OdinId?) -> String,
    isMine: (PostCommentItem) -> Boolean,
    onToggleCommentReaction: (PostCommentItem, String) -> Unit,
    onReply: (PostCommentItem) -> Unit,
    onEdit: (PostCommentItem, String) -> Unit,
    onDelete: (PostCommentItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topLevel = comments.filter { it.replyToId == null }
    val repliesByParent: Map<Uuid, List<PostCommentItem>> =
        comments.filter { it.replyToId != null }.groupBy { it.replyToId!! }

    // Which comment (if any) is in inline-edit mode. Owned here so the field's draft
    // and Save/Cancel are local UI state — the VM only hears about the final body.
    var editingId by remember { mutableStateOf<Uuid?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        topLevel.forEach { comment ->
            CommentRow(
                comment = comment,
                displayName = displayNameFor(comment.originalAuthor ?: comment.senderOdinId),
                isMine = isMine(comment),
                canReply = true,
                isEditing = editingId == comment.id,
                onToggleReaction = { emoji -> onToggleCommentReaction(comment, emoji) },
                onReply = { onReply(comment) },
                onStartEdit = { editingId = comment.id },
                onSaveEdit = { newBody ->
                    editingId = null
                    onEdit(comment, newBody)
                },
                onCancelEdit = { editingId = null },
                onDelete = { onDelete(comment) },
            )
            repliesByParent[comment.id].orEmpty().forEach { reply ->
                CommentRow(
                    comment = reply,
                    displayName = displayNameFor(reply.originalAuthor ?: reply.senderOdinId),
                    isMine = isMine(reply),
                    canReply = false,
                    isEditing = editingId == reply.id,
                    onToggleReaction = { emoji -> onToggleCommentReaction(reply, emoji) },
                    onReply = {},
                    onStartEdit = { editingId = reply.id },
                    onSaveEdit = { newBody ->
                        editingId = null
                        onEdit(reply, newBody)
                    },
                    onCancelEdit = { editingId = null },
                    onDelete = { onDelete(reply) },
                    modifier = Modifier.padding(start = 40.dp),
                )
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: PostCommentItem,
    displayName: String,
    isMine: Boolean,
    canReply: Boolean,
    isEditing: Boolean,
    onToggleReaction: (String) -> Unit,
    onReply: () -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val avatarOdinId = comment.originalAuthor ?: comment.senderOdinId

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (avatarOdinId != null) {
            PublicAvatar(
                odinId = avatarOdinId,
                initials = displayName.initials(),
                options = AvatarOptions(size = 28.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            // Web `Comment`: a rounded bubble holds the author name + body/media (the avatar sits
            // outside, to the left). The like/reply/meta row lives BELOW the bubble (web
            // `CommentMeta`). M3 styling: a faint surfaceContainerLow bubble, 12dp corners.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (isEditing) {
                        // Inline edit — replaces the rendered body until Save/Cancel. Prefilled
                        // with the current body, kept across recomposition by keying on the id.
                        var draft by remember(comment.id) { mutableStateOf(comment.body) }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onCancelEdit) {
                                Text(
                                    text = stringResource(MR.string.cancel),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { onSaveEdit(draft) },
                                enabled = draft.isNotBlank(),
                            ) {
                                Text(
                                    text = stringResource(MR.string.save),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    } else {
                        if (comment.body.isNotBlank()) {
                            ChatMarkdown(
                                content = comment.body,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        CommentMedia(comment = comment)
                    }
                }
            }

            // Web `CommentMeta`: a tight row of text affordances + timestamp, BELOW the bubble,
            // aligned with the bubble's content. Like/Reply are text links (web uses text, not
            // icons); edit/delete collapse into a MoreVert overflow menu (web ActionGroup).
            if (!isEditing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(MR.string.feed_comment_like),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onToggleReaction(COMMENT_LIKE_EMOJI) },
                    )
                    if (canReply) {
                        Text(
                            text = stringResource(MR.string.feed_comment_reply),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(onClick = onReply),
                        )
                    }
                    if (isMine) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { menuOpen = !menuOpen },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(
                                        MR.string.feed_post_detail_more_actions,
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(MR.string.edit)) },
                                    onClick = {
                                        menuOpen = false
                                        onStartEdit()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(MR.string.delete)) },
                                    onClick = {
                                        menuOpen = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = formatTimestamp(Instant.fromEpochMilliseconds(comment.createdMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Renders a comment's single attached image (the payload whose key matches
 * [PostCommentItem.mediaPayloadKey]) through the feed-shaped [MomentMediaGallery].
 * Renders nothing when the comment has no media payload.
 */
@Composable
private fun CommentMedia(comment: PostCommentItem) {
    val mediaKey = comment.mediaPayloadKey ?: return
    val mediaPayloads: List<PayloadDescriptor> =
        comment.payloads.filter { it.key == mediaKey }
    if (mediaPayloads.isEmpty()) return

    MomentMediaGallery(
        payloads = mediaPayloads,
        fileId = comment.fileId,
        driveId = comment.driveId,
        previewThumbnail = comment.previewThumbnail,
        keyHeader = comment.keyHeader,
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp)),
        sharedTransitionScope = null,
        animatedVisibilityScope = null,
        messageId = comment.id,
        downloadingFiles = emptySet(),
    )
}
