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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
                isLiked = false,
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

        // Comments — placeholder always-on section. Wire to
        // `MomentPostContent.commentsEnabled` once that flag round-trips.
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            CommentsHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // Comment list is always empty for now — no comments service yet.
        item {
            CommentsEmpty(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        item {
            AddCommentRow(
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
    isLiked: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = { /* TODO: toggle like */ },
            label = {
                Icon(
                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(MR.string.moments_reaction_like),
                    modifier = Modifier.size(18.dp),
                )
            },
            colors = AssistChipDefaults.assistChipColors(),
        )
        listOf("😂", "😮", "😢", "🔥").forEach { emoji ->
            AssistChip(
                onClick = { /* TODO: emoji react */ },
                label = { Text(emoji) },
            )
        }
    }
}

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
private fun AddCommentRow(modifier: Modifier = Modifier) {
    var draft by remember { mutableStateOf("") }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text(stringResource(MR.string.moments_detail_add_comment_hint)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        IconButton(onClick = { /* TODO: post comment */ }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(MR.string.moments_detail_send_comment),
            )
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
