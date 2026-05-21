package id.homebase.core.ui.screens.moments

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.widget.FullScreenMediaViewer
import id.homebase.chat.widget.FullScreenVideoPlayer
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.image.ImageSize
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.ui.screens.moments.widget.MomentMediaItem
import id.homebase.core.widget.DialogButtons
import id.homebase.core.widget.DialogCard
import id.homebase.core.widget.DialogTitle
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_message_delete_for_everyone
import id.homebase.resources.chat_message_delete_for_me
import id.homebase.resources.delivered_to
import id.homebase.resources.failed
import id.homebase.resources.menu_back
import id.homebase.resources.moments_detail_add_comment_hint
import id.homebase.resources.moments_detail_comment_cancel
import id.homebase.resources.moments_detail_comment_delete
import id.homebase.resources.moments_detail_comment_edit
import id.homebase.resources.moments_detail_comment_save
import id.homebase.resources.moments_detail_comment_you
import id.homebase.resources.moments_detail_comments_section
import id.homebase.resources.moments_detail_delete_comment_dialog_title
import id.homebase.resources.moments_detail_delete_dialog_title
import id.homebase.resources.moments_detail_menu_delete
import id.homebase.resources.moments_detail_menu_more
import id.homebase.resources.moments_detail_metadata_captured
import id.homebase.resources.moments_detail_no_comments
import id.homebase.resources.moments_detail_no_description
import id.homebase.resources.moments_detail_send_comment
import id.homebase.resources.moments_detail_shared_with
import id.homebase.resources.moments_detail_shared_with_conversation_fallback
import id.homebase.resources.moments_detail_shared_with_group_members
import id.homebase.resources.moments_detail_shared_with_hide
import id.homebase.resources.moments_detail_shared_with_private
import id.homebase.resources.moments_detail_shared_with_show
import id.homebase.resources.moments_label
import id.homebase.resources.moments_reaction_like
import id.homebase.resources.read_by
import id.homebase.resources.sending_to
import id.homebase.resources.uploaded
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Page 2 — Post Detail View.
 *
 * Subscribes to [MomentDetailViewModel] which sources from the live moments
 * feed. Layout mirrors the spec: media → reactions → description → metadata
 * → comments. Comments visibility is currently always-on (skeleton) —
 * `commentsEnabled` should round-trip through `MomentPostContent` and gate
 * the section once that schema lands.
 *
 * Named `Pane` because it's also embedded as the right pane of the desktop
 * wide-screen feed (see `MomentsScreen`) — the route-based full-screen
 * presentation and the embedded pane share this composable. The caller picks
 * the VM (parameterized by `momentId`) and supplies `onNavigateBack`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MomentDetailPane(
    viewModel: MomentDetailViewModel,
    onNavigateBack: (() -> Unit)?,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Pop the detail screen as soon as the delete completes. The optimistic
    // writer has already removed the moment from the feed by this point;
    // navigating back avoids a brief flash of the empty loading state.
    // Embedded (desktop wide) panes have no nav target, so the selection
    // change driven by the feed itself takes care of removing the deleted
    // moment from view.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is MomentDetailUiEvent.MomentDeleted) onNavigateBack?.invoke()
        }
    }

    // SharedTransitionLayout + AnimatedContent mirrors `ConversationMessagesPane`:
    // when fullScreenOverlay is null we render the regular detail screen;
    // otherwise the appropriate full-screen viewer fades in over it.
    SharedTransitionLayout {
        AnimatedContent(
            targetState = uiState.fullScreenOverlay,
            contentKey = { overlay ->
                when (overlay) {
                    null -> "detail"
                    is FullScreenOverlay.ViewMessageData -> "image"
                    is FullScreenOverlay.VideoPlayerData -> "video"
                    is FullScreenOverlay.AttachmentData -> "attachment"
                }
            },
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
        ) { overlay ->
            when (overlay) {
                null -> DetailContent(
                    uiState = uiState,
                    onAction = viewModel::onAction,
                    onNavigateBack = onNavigateBack,
                )

                is FullScreenOverlay.ViewMessageData -> FullScreenMediaViewer(
                    data = overlay,
                    isDownloading = false,
                    // TODO: wire share / save / delete to a moments action service.
                    onShare = { _, _ -> },
                    onSave = { _, _ -> },
                    onDelete = { },
                    onDismiss = {
                        viewModel.onAction(MomentDetailUiAction.CloseFullScreenOverlay)
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                )

                is FullScreenOverlay.VideoPlayerData -> FullScreenVideoPlayer(
                    data = overlay,
                    isDownloading = false,
                    // TODO: wire save once the moments action service grows a download path.
                    onSave = { },
                    onDismiss = {
                        viewModel.onAction(MomentDetailUiAction.CloseFullScreenOverlay)
                    },
                    uploadStatus = null,
                )

                is FullScreenOverlay.AttachmentData -> {
                    // Not used by moments — that overlay is the chat composer's
                    // attachment editor. The VM never emits this variant.
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailContent(
    uiState: MomentDetailUiState,
    onAction: (MomentDetailUiAction) -> Unit,
    onNavigateBack: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.moments_label)) },
                navigationIcon = {
                    // Embedded (desktop wide) pane: no nav target, so suppress
                    // the back arrow rather than offering a tap that does
                    // nothing meaningful.
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.string.menu_back),
                            )
                        }
                    }
                },
                actions = {
                    // Show overflow only once the moment has loaded — there's
                    // nothing actionable until then, and the menu would race
                    // with the loading spinner.
                    if (uiState.moment != null) {
                        MomentOverflowMenu(
                            isDeleting = uiState.isDeleting,
                            onDeleteClick = {
                                onAction(MomentDetailUiAction.RequestDeleteMoment)
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val moment = uiState.moment
        if (moment == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isLoading) CircularProgressIndicator()
            }
        } else {
            MomentDetailContent(
                uiState = uiState,
                moment = moment,
                initialPayloadKey = uiState.initialPayloadKey,
                onAction = onAction,
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding),
            )
        }
    }

    if (uiState.showDeleteDialog) {
        DeleteMomentDialog(
            allowDeleteForEveryone = uiState.isMine,
            onDeleteForMe = {
                onAction(MomentDetailUiAction.ConfirmDeleteMoment(forEveryone = false))
            },
            onDeleteForEveryone = {
                onAction(MomentDetailUiAction.ConfirmDeleteMoment(forEveryone = true))
            },
            onDismiss = { onAction(MomentDetailUiAction.DismissDeleteDialog) },
        )
    }

    val deleteCommentTarget = uiState.deleteCommentDialogTarget
    if (deleteCommentTarget != null) {
        DeleteCommentDialog(
            onDeleteForMe = {
                onAction(
                    MomentDetailUiAction.ConfirmDeleteComment(
                        commentId = deleteCommentTarget,
                        forEveryone = false,
                    ),
                )
            },
            onDeleteForEveryone = {
                onAction(
                    MomentDetailUiAction.ConfirmDeleteComment(
                        commentId = deleteCommentTarget,
                        forEveryone = true,
                    ),
                )
            },
            onDismiss = { onAction(MomentDetailUiAction.DismissDeleteCommentDialog) },
        )
    }
}

@Composable
private fun MomentOverflowMenu(
    isDeleting: Boolean,
    onDeleteClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        // While a delete is in flight the icon is swapped for a spinner.
        // Same shape as the per-comment delete indicator: tells the user
        // "something is happening" during the brief window between
        // confirming the dialog and the optimistic write removing the
        // moment from the feed (which triggers the nav-pop).
        IconButton(onClick = { expanded = true }, enabled = !isDeleting) {
            if (isDeleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(MR.string.moments_detail_menu_more),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.moments_detail_menu_delete)) },
                onClick = {
                    expanded = false
                    onDeleteClick()
                },
            )
        }
    }
}

@Composable
private fun DeleteMomentDialog(
    allowDeleteForEveryone: Boolean,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(
            buttons = {
                DialogButtons(
                    primaryText = stringResource(MR.string.chat_message_delete_for_me),
                    onPrimaryClick = onDeleteForMe,
                    secondaryText = if (allowDeleteForEveryone) {
                        stringResource(MR.string.chat_message_delete_for_everyone)
                    } else null,
                    onSecondaryClick = {
                        if (allowDeleteForEveryone) onDeleteForEveryone()
                    },
                    tertiaryText = stringResource(MR.string.cancel),
                    onTertiaryClick = onDismiss,
                    showButtonsVertically = true,
                )
            },
        ) {
            DialogTitle(text = stringResource(MR.string.moments_detail_delete_dialog_title))
        }
    }
}

/**
 * Comment delete is currently only offered for own comments (placed next to
 * Edit), so the "Delete for everyone" affordance is always available — same
 * "for me / for everyone / cancel" shape as [DeleteMomentDialog].
 */
@Composable
private fun DeleteCommentDialog(
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(
            buttons = {
                DialogButtons(
                    primaryText = stringResource(MR.string.chat_message_delete_for_me),
                    onPrimaryClick = onDeleteForMe,
                    secondaryText = stringResource(MR.string.chat_message_delete_for_everyone),
                    onSecondaryClick = onDeleteForEveryone,
                    tertiaryText = stringResource(MR.string.cancel),
                    onTertiaryClick = onDismiss,
                    showButtonsVertically = true,
                )
            },
        ) {
            DialogTitle(text = stringResource(MR.string.moments_detail_delete_comment_dialog_title))
        }
    }
}

@Composable
private fun MomentDetailContent(
    uiState: MomentDetailUiState,
    moment: MomentFeedItem,
    initialPayloadKey: String?,
    onAction: (MomentDetailUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageCount = moment.payloads.size.coerceAtLeast(1)
    // Read once per (moment.id, initialPayloadKey) — `rememberPagerState`'s
    // `initialPage` only fires on first composition, so subsequent re-emissions
    // of the same moment (e.g. a description-edit replay) won't snap the user
    // back to the seeded page.
    val initialPage = remember(moment.id, initialPayloadKey) {
        if (initialPayloadKey == null) 0
        else moment.payloads
            .indexOfFirst { it.key == initialPayloadKey }
            .coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pageCount },
    )

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // Media — pager when there are multiple payloads, single MomentMediaItem otherwise.
        // Constrain to ~60% of the pane width on wide layouts (the desktop
        // embedded pane can be 800dp+) and cap at MediaMaxWidth. Below
        // MediaWideBreakpoint the media stays full-width so phones/narrow
        // tablets are unchanged.
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val mediaWidth = if (maxWidth >= MediaWideBreakpoint) {
                    (maxWidth * MediaWidthFraction).coerceAtMost(MediaMaxWidth)
                } else {
                    maxWidth
                }
                Box(
                    modifier = Modifier
                        .width(mediaWidth)
                        .align(Alignment.Center),
                ) {
                if (moment.payloads.isEmpty()) {
                    // Description-only moment — keep a square placeholder so the
                    // top-of-screen rhythm doesn't collapse.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    ) { page ->
                        val payload = moment.payloads[page]
                        MomentMediaItem(
                            payload = payload,
                            fileId = moment.fileId,
                            driveId = moment.driveId,
                            previewThumbnail = moment.previewThumbnail,
                            keyHeader = moment.keyHeader,
                            modifier = Modifier.fillMaxSize(),
                            imageSize = ImageSize.THUMB_XLARGE,
                            preserveAspectRatio = false,
                            messageId = moment.id,
                            shape = RectangleShape,
                            sharedTransitionScope = null,
                            animatedVisibilityScope = null,
                            onClick = {
                                onAction(MomentDetailUiAction.MediaClicked(payload.key))
                            },
                        )
                    }
                    if (moment.payloads.size > 1) {
                        PagerDots(
                            pageCount = moment.payloads.size,
                            currentPage = pagerState.currentPage,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                        )
                    }
                }
                }
            }
        }

        item {
            ReactionsRow(
                summary = moment.reactionPreview,
                quickReactions = uiState.userDefaultReactions,
                onToggle = { emoji ->
                    onAction(MomentDetailUiAction.ToggleReactionOnMoment(emoji))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        item {
            DescriptionSection(
                description = moment.description,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        item {
            MetadataSection(
                capturedAtMs = moment.userDateMs,
                sharedWith = uiState.sharedWith,
                sharedWithExpanded = uiState.sharedWithExpanded,
                onToggleSharedWith = { expanded ->
                    onAction(MomentDetailUiAction.ToggleSharedWithExpansion(expanded))
                },
                isMine = uiState.isMine,
                isTransferHistoryLoading = uiState.isTransferHistoryLoading,
                recipientDeliveries = uiState.recipientDeliveries,
                recipientAvatars = uiState.recipientAvatars,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Comments — live stream from MomentCommentsService via the VM.
        // Wire visibility to `MomentPostContent.commentsEnabled` once that
        // flag round-trips through the post pipeline.
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            CommentsHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (uiState.comments.isEmpty()) {
            item {
                CommentsEmpty(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        } else {
            // Service emits newest-first; reverse for chat-style chronological
            // ordering (oldest at top, newest just above the input).
            items(uiState.comments.asReversed(), key = { it.id.toString() }) { comment ->
                val isMine = comment.senderOdinId == null ||
                    (uiState.selfOdinId != null && comment.senderOdinId == uiState.selfOdinId)
                CommentRow(
                    comment = comment,
                    isMine = isMine,
                    isEditing = uiState.editingCommentId == comment.id,
                    editDraft = if (uiState.editingCommentId == comment.id) uiState.editingCommentDraft else "",
                    isSaving = uiState.isSavingCommentEdit,
                    isDeleting = uiState.deletingCommentIds.contains(comment.id),
                    quickReactions = uiState.userDefaultReactions,
                    onEditClick = { onAction(MomentDetailUiAction.StartEditComment(comment.id)) },
                    onEditDraftChanged = { onAction(MomentDetailUiAction.EditCommentDraftChanged(it)) },
                    onSaveEdit = { onAction(MomentDetailUiAction.SaveCommentEdit) },
                    onCancelEdit = { onAction(MomentDetailUiAction.CancelCommentEdit) },
                    onDeleteClick = {
                        onAction(MomentDetailUiAction.RequestDeleteComment(comment.id))
                    },
                    onToggleReaction = { emoji ->
                        onAction(MomentDetailUiAction.ToggleReactionOnComment(comment.id, emoji))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item {
            AddCommentRow(
                draft = uiState.commentDraft,
                isPosting = uiState.isPostingComment,
                onDraftChanged = { onAction(MomentDetailUiAction.CommentDraftChanged(it)) },
                onSend = { onAction(MomentDetailUiAction.PostComment) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

// Width budget for the detail-pane media block. Below MediaWideBreakpoint the
// media takes the full pane width (today's mobile behavior); at or above, the
// media renders at MediaWidthFraction of the pane, capped at MediaMaxWidth so
// it never feels overwhelming on a 1500dp+ desktop right pane.
private val MediaWideBreakpoint = 600.dp
private const val MediaWidthFraction = 0.6f
private val MediaMaxWidth = 560.dp

@Composable
private fun PagerDots(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(pageCount) { i ->
            val active = i == currentPage
            Box(
                modifier = Modifier
                    .size(if (active) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) Color.White else Color.White.copy(alpha = 0.5f),
                    ),
            )
        }
    }
}

@Composable
private fun ReactionsRow(
    summary: id.homebase.api.client.drives.files.ReactionSummary?,
    quickReactions: List<String>,
    onToggle: (emoji: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = summary?.reactions?.values
        ?.mapNotNull { entry ->
            val emoji = decodeReactionEmoji(entry.reactionContent) ?: return@mapNotNull null
            emoji to entry.count
        }
        ?.filter { it.second > 0 }
        ?: emptyList()

    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Existing reactions: one chip per emoji with its count. Tapping
        // re-toggles the same emoji (adds the user's reaction if they don't
        // already have one, removes it otherwise — server reconciles).
        entries.forEach { (emoji, count) ->
            AssistChip(
                onClick = { onToggle(emoji) },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(emoji)
                        if (count > 1) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
            )
        }

        // "+" affordance opens a small popup of quick-react emojis sourced
        // from the user's preferred set (same store the chat composer uses).
        Box {
            AssistChip(
                onClick = { showPicker = true },
                label = {
                    Icon(
                        imageVector = Icons.Default.AddReaction,
                        contentDescription = stringResource(MR.string.moments_reaction_like),
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(),
            )
            DropdownMenu(
                expanded = showPicker,
                onDismissRequest = { showPicker = false },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    quickReactions.forEach { emoji ->
                        TextButton(
                            onClick = {
                                showPicker = false
                                onToggle(emoji)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(emoji, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun decodeReactionEmoji(reactionContent: String): String? = runCatching {
    id.homebase.api.serialization.OdinSystemSerializer
        .deserialize<id.homebase.api.client.drives.files.reactions.ReactionContent>(reactionContent)
        .emoji
}.getOrNull()

@Composable
private fun DescriptionSection(
    description: String,
    modifier: Modifier = Modifier,
) {
    if (description.isBlank()) {
        Text(
            text = stringResource(MR.string.moments_detail_no_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    } else {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier,
        )
    }
}

@Composable
private fun MetadataSection(
    capturedAtMs: Long,
    sharedWith: SharedWithDisplay?,
    sharedWithExpanded: Boolean,
    onToggleSharedWith: (expanded: Boolean) -> Unit,
    isMine: Boolean,
    isTransferHistoryLoading: Boolean,
    recipientDeliveries: List<RecipientDeliveryUiModel>,
    recipientAvatars: List<RecipientBaseUiModel>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MetadataRow(
            label = stringResource(MR.string.moments_detail_metadata_captured),
            value = formatCapturedAt(capturedAtMs),
        )
        // Device row intentionally omitted — the post sender doesn't capture
        // device info today. Add when the schema does.
        if (sharedWith != null) {
            SharedWithRow(
                sharedWith = sharedWith,
                expanded = sharedWithExpanded,
                onToggleExpansion = onToggleSharedWith,
                isMine = isMine,
                isTransferHistoryLoading = isTransferHistoryLoading,
                recipientDeliveries = recipientDeliveries,
                recipientAvatars = recipientAvatars,
            )
        }
    }
}

/**
 * Top-level "Shared with" surface. Routes Private to a lock-icon row;
 * everything else renders an avatar stack with an expand affordance and a
 * details list keyed off [isMine]:
 *  - authored moments expose per-recipient delivery status, grouped by state,
 *    via [DeliveryStatusSection];
 *  - received moments fall back to a plain avatar + name list, since
 *    transfer history is only returned to the file's author.
 *
 * Audience-level context (Group, Conversation) shows above the per-recipient
 * rows so the user can still tell the post was framed around a group/chat
 * even though the recipients are listed individually. Individual entries are
 * intentionally dropped from the context section — they would just duplicate
 * the per-recipient rows below.
 */
@Composable
private fun SharedWithRow(
    sharedWith: SharedWithDisplay,
    expanded: Boolean,
    onToggleExpansion: (Boolean) -> Unit,
    isMine: Boolean,
    isTransferHistoryLoading: Boolean,
    recipientDeliveries: List<RecipientDeliveryUiModel>,
    recipientAvatars: List<RecipientBaseUiModel>,
) {
    when (sharedWith) {
        SharedWithDisplay.Private -> PrivateRow()
        is SharedWithDisplay.Recipients -> RecipientsRow(
            entries = sharedWith.entries,
            recipientAvatars = recipientAvatars,
            expanded = expanded,
            onToggleExpansion = onToggleExpansion,
            isMine = isMine,
            isTransferHistoryLoading = isTransferHistoryLoading,
            recipientDeliveries = recipientDeliveries,
        )
    }
}

@Composable
private fun PrivateRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(MR.string.moments_detail_shared_with_private),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecipientsRow(
    entries: List<SharedWithEntry>,
    recipientAvatars: List<RecipientBaseUiModel>,
    expanded: Boolean,
    onToggleExpansion: (Boolean) -> Unit,
    isMine: Boolean,
    isTransferHistoryLoading: Boolean,
    recipientDeliveries: List<RecipientDeliveryUiModel>,
) {
    // Group/Conversation entries survive into the expanded view as context
    // lines; Individual entries don't — the avatar stack (collapsed) and the
    // per-recipient rows (expanded) already cover them, and rendering both
    // was the source of the duplicate-names complaint.
    val contextEntries = remember(entries) {
        entries.filter { it is SharedWithEntry.Group || it is SharedWithEntry.Conversation }
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpansion(!expanded) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(MR.string.moments_detail_shared_with),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AvatarStack(
                recipients = recipientAvatars,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) MR.string.moments_detail_shared_with_hide
                    else MR.string.moments_detail_shared_with_show,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            if (contextEntries.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    contextEntries.forEach { entry -> SharedWithEntryRow(entry) }
                }
            }
            if (isMine) {
                DeliveryStatusSection(
                    isLoading = isTransferHistoryLoading,
                    deliveries = recipientDeliveries,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else if (recipientAvatars.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recipientAvatars.forEach { recipient ->
                        RecipientPlainRow(recipient)
                    }
                }
            }
        }
    }
}

/**
 * Overlapping circle row of recipient avatars, capped at [maxVisible] with a
 * `+N` chip after the cap. Negative horizontal spacing on the parent Row is
 * what creates the layered look — each avatar sits on a small surface-colored
 * disc one pixel wider than the avatar so adjacent edges read as separations
 * rather than overlapping pixels.
 */
@Composable
private fun AvatarStack(
    recipients: List<RecipientBaseUiModel>,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 24.dp,
    maxVisible: Int = 5,
) {
    if (recipients.isEmpty()) return
    val visible = recipients.take(maxVisible)
    val overflow = recipients.size - visible.size
    val ringColor = MaterialTheme.colorScheme.surface
    val ringSize = avatarSize + 2.dp
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(-(avatarSize / 3)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEach { r ->
            Box(
                modifier = Modifier
                    .size(ringSize)
                    .clip(CircleShape)
                    .background(ringColor),
                contentAlignment = Alignment.Center,
            ) {
                PublicAvatar(
                    odinId = r.odinId,
                    initials = r.displayName.firstOrNull()?.toString(),
                    options = AvatarOptions(size = avatarSize),
                )
            }
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .size(ringSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun RecipientPlainRow(recipient: RecipientBaseUiModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PublicAvatar(
            odinId = recipient.odinId,
            initials = recipient.displayName.firstOrNull()?.toString(),
            options = AvatarOptions(size = 32.dp),
        )
        Text(
            text = recipient.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Per-recipient delivery breakdown, grouped by [ChatDeliveryStatus]. The chat
 * MessageInfo screen renders the same shape — same status labels, same error
 * detail strings, same chat mappings — so the moments surface and the chat
 * surface stay consistent without duplicating the rendering helpers.
 *
 * Empty state intentionally renders nothing rather than a "no delivery
 * history yet" stub: an authored moment with recipients always has a history
 * row per recipient once the server has acknowledged the upload, so an empty
 * map either means the fetch hasn't completed (the loading spinner above
 * handles that case) or the post is brand-new (will populate on close+reopen).
 */
@Composable
private fun DeliveryStatusSection(
    isLoading: Boolean,
    deliveries: List<RecipientDeliveryUiModel>,
    modifier: Modifier = Modifier,
) {
    if (isLoading) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        return
    }
    if (deliveries.isEmpty()) return

    val grouped = deliveries.groupBy { it.deliveryStatus }
    val statusOrder = listOf(
        ChatDeliveryStatus.Sending,
        ChatDeliveryStatus.Sent,
        ChatDeliveryStatus.Delivered,
        ChatDeliveryStatus.Read,
        ChatDeliveryStatus.Failed,
    )
    val allStatuses = statusOrder + (grouped.keys - statusOrder.toSet())

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        allStatuses.forEach { status ->
            val rows = grouped[status] ?: return@forEach
            val label = when (status) {
                ChatDeliveryStatus.Read -> stringResource(MR.string.read_by)
                ChatDeliveryStatus.Delivered -> stringResource(MR.string.delivered_to)
                ChatDeliveryStatus.Sent -> stringResource(MR.string.uploaded)
                ChatDeliveryStatus.Sending -> stringResource(MR.string.sending_to)
                ChatDeliveryStatus.Failed -> stringResource(MR.string.failed)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            rows.forEach { row -> DeliveryRow(row) }
        }
    }
}

@Composable
private fun DeliveryRow(row: RecipientDeliveryUiModel) {
    val odinId = remember(row.odinId) { OdinId(row.odinId) }
    val errorText = row.errorDetailRes?.let { stringResource(it) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PublicAvatar(
            odinId = odinId,
            initials = row.displayName.firstOrNull()?.toString(),
            options = AvatarOptions(size = 32.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SharedWithEntryRow(entry: SharedWithEntry) {
    val icon: ImageVector = when (entry) {
        is SharedWithEntry.Group -> Icons.Default.Group
        is SharedWithEntry.Individual -> Icons.Default.Person
        is SharedWithEntry.Conversation -> Icons.AutoMirrored.Filled.Chat
    }
    val label: String = when (entry) {
        is SharedWithEntry.Group -> entry.name
        is SharedWithEntry.Individual -> entry.name
        is SharedWithEntry.Conversation ->
            entry.name ?: stringResource(MR.string.moments_detail_shared_with_conversation_fallback)
    }
    val subLabel: String? = when (entry) {
        is SharedWithEntry.Group -> pluralStringResource(
            MR.plurals.moments_detail_shared_with_group_members,
            entry.memberCount,
            entry.memberCount,
        )
        else -> null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (subLabel != null) {
            Text(
                text = subLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CommentsHeader(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(MR.string.moments_detail_comments_section),
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier,
    )
}

@Composable
private fun CommentsEmpty(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(MR.string.moments_detail_no_comments),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun AddCommentRow(
    draft: String,
    isPosting: Boolean,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = draft.isNotBlank() && !isPosting
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChanged,
            placeholder = { Text(stringResource(MR.string.moments_detail_add_comment_hint)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = !isPosting,
        )
        IconButton(onClick = onSend, enabled = canSend) {
            if (isPosting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(MR.string.moments_detail_send_comment),
                )
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: id.homebase.core.moments.services.MomentCommentItem,
    isMine: Boolean,
    isEditing: Boolean,
    editDraft: String,
    isSaving: Boolean,
    isDeleting: Boolean,
    quickReactions: List<String>,
    onEditClick: () -> Unit,
    onEditDraftChanged: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDeleteClick: () -> Unit,
    onToggleReaction: (emoji: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isMine) stringResource(MR.string.moments_detail_comment_you)
                else comment.senderOdinId?.domainName ?: "",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatCapturedAt(comment.userDateMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isEditing) {
            // Inline edit field — replaces the body until Save or Cancel.
            // Disabled while a save is in flight so the user can't double-tap.
            OutlinedTextField(
                value = editDraft,
                onValueChange = onEditDraftChanged,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onCancelEdit,
                    enabled = !isSaving,
                ) {
                    Text(stringResource(MR.string.moments_detail_comment_cancel))
                }
                TextButton(
                    onClick = onSaveEdit,
                    enabled = !isSaving && editDraft.isNotBlank(),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(MR.string.moments_detail_comment_save))
                    }
                }
            }
        } else {
            Text(
                text = comment.body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            // Reaction pills + react affordance. Same toggle-on-tap shape as
            // the moment-level row, just denser (smaller chips, smaller
            // picker target).
            CommentReactionsRow(
                summary = comment.reactionPreview,
                quickReactions = quickReactions,
                onToggle = onToggleReaction,
            )

            // Action row: edit + delete (own comments only, only once
            // versionTag is confirmed). Sits next to the reactions so the
            // controls cluster. While a delete is in flight both buttons
            // disable and a small spinner sits at the end — the row is
            // usually about to vanish (optimistic delete) but if the enqueue
            // fails it stays around, so we need the disable state.
            if (isMine && comment.versionTag != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onEditClick,
                        enabled = !isDeleting,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(MR.string.moments_detail_comment_edit))
                    }
                    TextButton(
                        onClick = onDeleteClick,
                        enabled = !isDeleting,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(MR.string.moments_detail_comment_delete))
                    }
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentReactionsRow(
    summary: id.homebase.api.client.drives.files.ReactionSummary?,
    quickReactions: List<String>,
    onToggle: (emoji: String) -> Unit,
) {
    val entries = summary?.reactions?.values
        ?.mapNotNull { entry ->
            val emoji = decodeReactionEmoji(entry.reactionContent) ?: return@mapNotNull null
            emoji to entry.count
        }
        ?.filter { it.second > 0 }
        ?: emptyList()

    var showPicker by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { (emoji, count) ->
            AssistChip(
                onClick = { onToggle(emoji) },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(emoji, style = MaterialTheme.typography.labelMedium)
                        if (count > 1) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
            )
        }

        Box {
            // Compact text-button "+ react" rather than a full chip, to keep
            // the comment row dense.
            TextButton(
                onClick = { showPicker = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AddReaction,
                    contentDescription = stringResource(MR.string.moments_reaction_like),
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = showPicker,
                onDismissRequest = { showPicker = false },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    quickReactions.forEach { emoji ->
                        TextButton(
                            onClick = {
                                showPicker = false
                                onToggle(emoji)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(emoji, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

private val capturedAtFormat = kotlinx.datetime.LocalDateTime.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    day()
    chars(", ")
    year()
    chars(" · ")
    amPmHour()
    char(':')
    minute()
    char(' ')
    amPmMarker(am = "AM", pm = "PM")
}

private fun formatCapturedAt(epochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return capturedAtFormat.format(local)
}
