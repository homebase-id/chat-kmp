package id.homebase.photobase.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.formatMomentDate
import id.homebase.core.widget.AddReactionChip
import id.homebase.core.widget.EmojiSelection
import id.homebase.core.widget.ReactionList
import id.homebase.core.widget.ReactionMenu
import id.homebase.api.util.truncateToCodePoints
import id.homebase.photobase.social.MAX_COMMENT_CODE_POINTS
import id.homebase.photobase.social.PhotoComment
import id.homebase.photobase.social.PhotoReactions
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.delete
import id.homebase.resources.edit
import id.homebase.resources.photobase_comments_byline
import id.homebase.resources.photobase_comments_editing
import id.homebase.resources.photobase_comments_empty
import id.homebase.resources.photobase_comments_hint
import id.homebase.resources.photobase_comments_more
import id.homebase.resources.photobase_comments_open
import id.homebase.resources.photobase_comments_title
import id.homebase.resources.save
import id.homebase.resources.send
import id.homebase.resources.you
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CommentsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = stringResource(MR.string.photobase_comments_open))
    }
}

/** The photo's reactions and comments, and a field to add one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoCommentsSheet(
    photoId: Uuid,
    comments: Flow<List<PhotoComment>>,
    reactionsOf: (Uuid) -> Flow<PhotoReactions>,
    onAction: (PhotoGalleryUiAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val shown by comments.collectAsStateWithLifecycle(null)
    // The comment or photo whose emoji menu is open.
    var reactingTo by remember { mutableStateOf<Uuid?>(null) }
    var pickingAll by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PhotoComment?>(null) }
    var draft by remember { mutableStateOf("") }
    val react = { target: Uuid, emoji: String ->
        onAction(PhotoGalleryUiAction.ToggleReaction(target, emoji))
        reactingTo = null
        pickingAll = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 640.dp) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Text(
                stringResource(MR.string.photobase_comments_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Reactions(photoId, reactionsOf, onReact = { reactingTo = photoId }, modifier = Modifier.padding(horizontal = 12.dp))
            reactingTo?.let { target ->
                if (pickingAll) {
                    Box(Modifier.heightIn(max = 320.dp)) { EmojiSelection(onEmojiSelected = { react(target, it) }) }
                } else {
                    ReactionMenu(onSelect = { react(target, it) }, onShowAllEmojis = { pickingAll = true }, modifier = Modifier.padding(8.dp))
                }
            }
            val list = shown
            when {
                list == null -> Unit
                list.isEmpty() -> Text(
                    stringResource(MR.string.photobase_comments_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(list, key = { it.id.toString() }) { comment ->
                        CommentRow(
                            comment,
                            reactionsOf,
                            onReact = { reactingTo = comment.id; pickingAll = false },
                            onEdit = { editing = comment; draft = comment.body }.takeIf { comment.senderOdinId == null && comment.versionTag != null },
                            onDelete = { onAction(PhotoGalleryUiAction.DeleteComment(comment.id)) }.takeIf { comment.senderOdinId == null },
                        )
                    }
                }
            }
            CommentField(
                draft = draft,
                onDraft = { draft = it },
                editing = editing != null,
                onCancelEdit = { editing = null; draft = "" },
                onSend = {
                    val body = draft.trim()
                    if (body.isNotEmpty()) {
                        val edited = editing
                        val tag = edited?.versionTag
                        onAction(if (edited != null && tag != null) PhotoGalleryUiAction.EditComment(edited.id, tag, body) else PhotoGalleryUiAction.PostComment(photoId, body))
                        editing = null
                        draft = ""
                    }
                },
            )
        }
    }
}

@Composable
private fun Reactions(targetId: Uuid, reactionsOf: (Uuid) -> Flow<PhotoReactions>, onReact: () -> Unit, modifier: Modifier = Modifier) {
    val reactions by remember(targetId) { reactionsOf(targetId) }.collectAsStateWithLifecycle(PhotoReactions())
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        reactions.summary?.let { summary ->
            ReactionList(reactionSummary = summary, onReactionClick = onReact, ownReactions = reactions.own)
        }
        AddReactionChip(onClick = onReact)
    }
}

@Composable
private fun CommentRow(
    comment: PhotoComment,
    reactionsOf: (Uuid) -> Flow<PhotoReactions>,
    onReact: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val who = comment.senderOdinId?.domainName ?: stringResource(MR.string.you)
            val byline = stringResource(MR.string.photobase_comments_byline, who, formatMomentDate(Instant.fromEpochMilliseconds(comment.userDateMs)))
            Text(byline, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(comment.body, style = MaterialTheme.typography.bodyMedium)
            Reactions(comment.id, reactionsOf, onReact)
        }
        if (onEdit != null || onDelete != null) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(MR.string.photobase_comments_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    onEdit?.let { edit -> DropdownMenuItem(text = { Text(stringResource(MR.string.edit)) }, onClick = { menu = false; edit() }) }
                    onDelete?.let { delete -> DropdownMenuItem(text = { Text(stringResource(MR.string.delete)) }, onClick = { menu = false; delete() }) }
                }
            }
        }
    }
}

@Composable
private fun CommentField(draft: String, onDraft: (String) -> Unit, editing: Boolean, onCancelEdit: () -> Unit, onSend: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(MR.string.photobase_comments_editing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancelEdit) { Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.cancel)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { onDraft(it.truncateToCodePoints(MAX_COMMENT_CODE_POINTS)) },
                placeholder = { Text(stringResource(MR.string.photobase_comments_hint)) },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(if (editing) MR.string.save else MR.string.send),
                )
            }
        }
    }
}
