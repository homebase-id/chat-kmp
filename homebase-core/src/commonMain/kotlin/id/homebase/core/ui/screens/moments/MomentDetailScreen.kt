package id.homebase.core.ui.screens.moments

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.widget.FullScreenMediaViewer
import id.homebase.chat.widget.FullScreenVideoPlayer
import id.homebase.core.image.ImageSize
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.ui.screens.moments.widget.MomentMediaItem
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.moments_detail_add_comment_hint
import id.homebase.resources.moments_detail_comment_cancel
import id.homebase.resources.moments_detail_comment_edit
import id.homebase.resources.moments_detail_comment_save
import id.homebase.resources.moments_detail_comment_you
import id.homebase.resources.moments_detail_comments_section
import id.homebase.resources.moments_detail_metadata_captured
import id.homebase.resources.moments_detail_no_comments
import id.homebase.resources.moments_detail_no_description
import id.homebase.resources.moments_detail_send_comment
import id.homebase.resources.moments_label
import id.homebase.resources.moments_reaction_like
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * Page 2 — Post Detail View.
 *
 * Subscribes to [MomentDetailViewModel] which sources from the live moments
 * feed. Layout mirrors the spec: media → reactions → description → metadata
 * → comments. Comments visibility is currently always-on (skeleton) —
 * `commentsEnabled` should round-trip through `MomentPostContent` and gate
 * the section once that schema lands.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MomentDetailScreen(
    viewModel: MomentDetailViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.moments_label)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
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
        item {
            Box(modifier = Modifier.fillMaxWidth()) {
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
                    quickReactions = uiState.userDefaultReactions,
                    onEditClick = { onAction(MomentDetailUiAction.StartEditComment(comment.id)) },
                    onEditDraftChanged = { onAction(MomentDetailUiAction.EditCommentDraftChanged(it)) },
                    onSaveEdit = { onAction(MomentDetailUiAction.SaveCommentEdit) },
                    onCancelEdit = { onAction(MomentDetailUiAction.CancelCommentEdit) },
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
    quickReactions: List<String>,
    onEditClick: () -> Unit,
    onEditDraftChanged: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
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

            // Action row: edit (own comments only, only once versionTag is
            // confirmed). Sits next to the reactions so the controls cluster.
            if (isMine && comment.versionTag != null) {
                TextButton(
                    onClick = onEditClick,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(MR.string.moments_detail_comment_edit))
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
