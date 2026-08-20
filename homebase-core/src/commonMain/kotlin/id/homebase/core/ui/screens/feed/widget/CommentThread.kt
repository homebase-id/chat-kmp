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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.chat.widget.ChatMarkdown
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.feed.services.CanReact
import id.homebase.core.feed.services.PostCommentItem
import id.homebase.core.ui.screens.moments.widget.MomentMediaGallery
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.initials
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.delete
import id.homebase.resources.edit
import id.homebase.resources.feed_comment_delete_confirm
import id.homebase.resources.feed_comment_like
import id.homebase.resources.feed_comment_reply
import id.homebase.resources.feed_post_block
import id.homebase.resources.feed_post_detail_more_actions
import id.homebase.resources.save
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val COMMENT_LIKE_EMOJI = "❤️"

private fun PostCommentItem.blockAction(
    isMine: Boolean,
    onBlockAuthor: (OdinId) -> Unit,
): (() -> Unit)? {
    if (isMine) return null
    val author = originalAuthor ?: senderOdinId ?: return null
    return { onBlockAuthor(author) }
}

// [permission] is one verdict for the whole post; null (unresolved) leaves Like and Reply visible.
@Composable
fun CommentThread(
    comments: List<PostCommentItem>,
    displayNameFor: (OdinId?) -> String,
    isMine: (PostCommentItem) -> Boolean,
    onToggleCommentReaction: (PostCommentItem, String) -> Unit,
    onReply: (PostCommentItem) -> Unit,
    onEdit: (PostCommentItem, String) -> Unit,
    onDelete: (PostCommentItem) -> Unit,
    onBlockAuthor: (OdinId) -> Unit,
    modifier: Modifier = Modifier,
    permission: CanReact? = null,
) {
    val topLevel = comments.filter { it.replyToId == null }
    val repliesByParent: Map<Uuid, List<PostCommentItem>> =
        comments.filter { it.replyToId != null }.groupBy { it.replyToId!! }

    // Owned here so the draft and Save/Cancel stay local UI state — the VM only hears the final body.
    var editingId by remember { mutableStateOf<Uuid?>(null) }

    // Keyed by comment id, not position: the thread is live, and CommentRow's remembered menu/delete-confirm
    // state would otherwise re-bind to whichever comment slid into the slot.
    Column(modifier = modifier.fillMaxWidth()) {
        topLevel.forEach { comment ->
            key(comment.id) {
                CommentRow(
                    comment = comment,
                    displayName = displayNameFor(comment.originalAuthor ?: comment.senderOdinId),
                    isMine = isMine(comment),
                    canReply = permission?.allowsComment ?: true,
                    isEditing = editingId == comment.id,
                    canLike = permission?.allowsEmoji ?: true,
                    onToggleReaction = { emoji -> onToggleCommentReaction(comment, emoji) },
                    onReply = { onReply(comment) },
                    onStartEdit = { editingId = comment.id },
                    onSaveEdit = { newBody ->
                        editingId = null
                        onEdit(comment, newBody)
                    },
                    onCancelEdit = { editingId = null },
                    onDelete = { onDelete(comment) },
                    onBlock = comment.blockAction(isMine(comment), onBlockAuthor),
                )
            }
            repliesByParent[comment.id].orEmpty().forEach { reply ->
                key(reply.id) {
                    CommentRow(
                        comment = reply,
                        displayName = displayNameFor(reply.originalAuthor ?: reply.senderOdinId),
                        isMine = isMine(reply),
                        canReply = false,
                        isEditing = editingId == reply.id,
                        canLike = permission?.allowsEmoji ?: true,
                        onToggleReaction = { emoji -> onToggleCommentReaction(reply, emoji) },
                        onReply = {},
                        onStartEdit = { editingId = reply.id },
                        onSaveEdit = { newBody ->
                            editingId = null
                            onEdit(reply, newBody)
                        },
                        onCancelEdit = { editingId = null },
                        onDelete = { onDelete(reply) },
                        onBlock = reply.blockAction(isMine(reply), onBlockAuthor),
                        modifier = Modifier.padding(start = 40.dp),
                    )
                }
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
    canLike: Boolean,
    onToggleReaction: (String) -> Unit,
    onReply: () -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
    onBlock: (() -> Unit)?,
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

            if (!isEditing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (canLike) {
                        Text(
                            text = stringResource(MR.string.feed_comment_like),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { onToggleReaction(COMMENT_LIKE_EMOJI) },
                        )
                    }
                    if (canReply) {
                        Text(
                            text = stringResource(MR.string.feed_comment_reply),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(onClick = onReply),
                        )
                    }
                    if (isMine || onBlock != null) {
                        var menuOpen by remember { mutableStateOf(false) }
                        var confirmDelete by remember { mutableStateOf(false) }
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
                                // Focusable so the popup owns input while it's open — without it this menu
                                // stayed open through repeated taps outside while hosted in the modal sheet.
                                properties = PopupProperties(focusable = true),
                            ) {
                                if (isMine) {
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
                                            confirmDelete = true
                                        },
                                    )
                                }
                                onBlock?.let { block ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(MR.string.feed_post_block)) },
                                        onClick = {
                                            menuOpen = false
                                            block()
                                        },
                                    )
                                }
                            }
                        }

                        // The web deletes straight from the menu; on touch that's one stray tap from losing it.
                        if (confirmDelete) {
                            AlertDialog(
                                onDismissRequest = { confirmDelete = false },
                                title = { Text(stringResource(MR.string.delete)) },
                                text = {
                                    Text(stringResource(MR.string.feed_comment_delete_confirm))
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            confirmDelete = false
                                            onDelete()
                                        },
                                    ) {
                                        Text(stringResource(MR.string.delete))
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
