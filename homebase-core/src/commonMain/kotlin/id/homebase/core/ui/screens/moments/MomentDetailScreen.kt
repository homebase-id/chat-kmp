package id.homebase.core.ui.screens.moments

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalLayoutDirection
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
import id.homebase.core.moments.MomentsPreferences
import id.homebase.core.moments.MomentsViewMode
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.moments.services.MomentsFeedService
import id.homebase.core.moments.services.MomentsVideoSession
import id.homebase.core.ui.screens.moments.widget.MomentDatePill
import id.homebase.core.ui.screens.moments.widget.MomentInlineVideoTile
import id.homebase.core.ui.screens.moments.widget.MomentMediaItem
import id.homebase.core.ui.screens.moments.widget.MomentVideoTapMode
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.drop
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.DialogButtons
import id.homebase.core.widget.DialogCard
import id.homebase.core.widget.DialogTitle
import kotlinx.io.files.Path
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
import id.homebase.resources.moments_detail_shared_with_more
import id.homebase.resources.moments_detail_shared_with_just_you
import id.homebase.resources.moments_detail_shared_with_private
import id.homebase.resources.moments_detail_shared_with_show
import id.homebase.resources.moments_detail_reactions_filter_all
import id.homebase.resources.moments_detail_reactions_see_who
import id.homebase.resources.moments_detail_reactions_sheet_empty
import id.homebase.resources.moments_label
import id.homebase.resources.moments_reaction_like
import id.homebase.resources.reactions
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
 * → comments (hidden when the author disabled commenting at post time).
 *
 * Named `Pane` because it's also embedded as the right pane of the desktop
 * wide-screen feed (see `MomentsScreen`) — the route-based full-screen
 * presentation and the embedded pane share this composable. The caller picks
 * the VM (parameterized by `momentId`) and supplies `onNavigateBack`.
 */
/**
 * Instagram-Reels-style vertical pager over the in-memory moments feed. The
 * user lands on the moment they tapped and can swipe up/down to walk through
 * adjacent moments without popping back to the feed list. Each page is a
 * fully-featured [MomentDetailPane] with its own [MomentDetailViewModel] —
 * comments, reactions, full-screen overlays, etc. all work per moment.
 *
 * Source list: [MomentsFeedService.feed], the same flow the feed list
 * subscribes to. No load-more here — the service keeps every moment for the
 * active drive in memory, so the pager surfaces exactly what's already
 * loaded.
 *
 * VM lifetime: each page allocates a Koin-scoped VM keyed by `momentId`. The
 * VMs are tied to the nav back-stack entry, so they outlive page
 * scroll-away but get cleared in bulk on back-press. With
 * [VerticalPager.beyondViewportPageCount] = 1 only three pages are in
 * composition at a time, but VMs slowly accumulate as the user pages — this
 * is bounded by the number of moments they actually visit in a single
 * detail session and acceptable in practice. If it becomes a memory
 * pressure point later, the fix is a per-page Koin scope that releases
 * VMs when the page leaves composition.
 *
 * @param initialMomentId the moment the user tapped to enter detail.
 * @param initialPayloadKey forwarded only to the initial page so a deep-link
 *   into a specific carousel item lands on that item. Adjacent moments
 *   reached by vertical swipe always start on their first payload.
 */
@Composable
fun MomentDetailPager(
    initialMomentId: Uuid,
    initialPayloadKey: String?,
    onNavigateBack: () -> Unit,
) {
    val feedService = koinInject<MomentsFeedService>()
    val momentsPreferences = koinInject<MomentsPreferences>()
    val feed by feedService.feed.collectAsStateWithLifecycle()
    val viewMode by momentsPreferences.viewMode.collectAsStateWithLifecycle()

    // The service emits its own canonical (userDate) order, but the feed
    // screens re-sort by view mode in [MomentsFeedViewModel] — Timeline by
    // createdMs, Album by userDateMs. We mirror that here so the pager's
    // order matches whatever ordering the user just left, and so the
    // initial-page index lookup against `initialMomentId` lands in the
    // right slot. (Without this, posts where createdMs differs from
    // userDateMs land in different positions across the two surfaces,
    // which reads as "doubles" / "wrong next moment" on vertical swipe.)
    val orderedFeed = remember(feed, viewMode) {
        when (viewMode) {
            MomentsViewMode.Timeline -> feed.sortedByDescending { it.createdMs }
            MomentsViewMode.Album -> feed.sortedByDescending { it.userDateMs }
        }
    }

    if (orderedFeed.isEmpty()) {
        // Cold-load may still be in flight (deep-link / process restart).
        // Black background to match the detail screen's container colour
        // so the transition into a loaded page doesn't flash a different
        // surface colour underneath.
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    MomentDetailLoadedPager(
        feed = orderedFeed,
        initialMomentId = initialMomentId,
        initialPayloadKey = initialPayloadKey,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Body of [MomentDetailPager], extracted so [rememberPagerState]'s
 * `initialPage` is computed against a known-non-empty feed. If we resolved
 * the index in the outer composable and feed was empty at first
 * composition, the pager would lock onto page 0 forever even after the
 * feed populated.
 */
@Composable
private fun MomentDetailLoadedPager(
    feed: List<MomentFeedItem>,
    initialMomentId: Uuid,
    initialPayloadKey: String?,
    onNavigateBack: () -> Unit,
) {
    // Resolve once. Subsequent feed updates (new posts arriving, optimistic
    // deletes) shift indices around but the pager's current page anchors to
    // whichever moment the user has settled on via its own state.
    val initialIndex = remember(initialMomentId) {
        feed.indexOfFirst { it.id == initialMomentId }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { feed.size },
    )

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        // Pre-mount the moments above and below so the swipe feels instant.
        // Larger windows compound the VM-accumulation footprint above — 1 is
        // enough for the eye to never see a blank page.
        beyondViewportPageCount = 1,
    ) { page ->
        val moment = feed[page]
        val pageMomentId = moment.id
        val isInitialPage = pageMomentId == initialMomentId
        val pageVm: MomentDetailViewModel = koinViewModel(
            key = "moment-detail-pager-$pageMomentId",
        ) {
            // Only the initial page receives the tapped payload key (deep-
            // link into a specific carousel item). Vertically-swiped-into
            // pages start on their own first payload.
            parametersOf(
                pageMomentId,
                if (isInitialPage) initialPayloadKey else null,
            )
        }
        MomentDetailPane(
            viewModel = pageVm,
            onNavigateBack = onNavigateBack,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MomentDetailPane(
    viewModel: MomentDetailViewModel,
    onNavigateBack: (() -> Unit)?,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val fileSystemHandler = getUriHandler()

    // Pop the detail screen as soon as the delete completes. The optimistic
    // writer has already removed the moment from the feed by this point;
    // navigating back avoids a brief flash of the empty loading state.
    // Embedded (desktop wide) panes have no nav target, so the selection
    // change driven by the feed itself takes care of removing the deleted
    // moment from view.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MomentDetailUiEvent.MomentDeleted -> onNavigateBack?.invoke()
                is MomentDetailUiEvent.ShareFileReady ->
                    fileSystemHandler.shareFile(Path(event.filePath))
                else -> Unit
            }
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
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                )

                is FullScreenOverlay.ViewMessageData -> FullScreenMediaViewer(
                    data = overlay,
                    isDownloading = false,
                    // TODO: wire save / delete to a moments action service.
                    onShare = { _, key ->
                        viewModel.onAction(MomentDetailUiAction.ShareMedia(key))
                    },
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
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    // Local sheet toggle — pure UI state. The reactor-roster sheet
    // (uiState.showReactionsSheet) is still VM-driven below because it loads
    // avatars from the server; heart / flame counts on the action column
    // are read directly off `moment.reactionPreview` so the user doesn't
    // need to open a sheet to see them.
    var showCommentsSheet by remember { mutableStateOf(false) }

    Scaffold(
        // Black so the letterbox bars around a Fit-scaled image or video
        // read as part of the cinematic surface rather than the surface
        // colour of the rest of the app.
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                // No screen title in the full-screen view — the post itself
                // is the title. Keep the bar transparent so it floats over
                // the media.
                title = {},
                navigationIcon = {
                    // Embedded (desktop wide) pane: no nav target, so suppress
                    // the back arrow rather than offering a tap that does
                    // nothing meaningful.
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.string.menu_back),
                                tint = Color.White,
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
                            iconTint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { innerPadding ->
        // Immersive layout — the TopAppBar above is transparent and meant to
        // float over the media (back arrow / overflow menu sit directly on
        // the photo or video). Zero out the top inset so the content extends
        // *behind* the bar instead of being pushed below it; keep the
        // horizontal + bottom insets so the bottom-overlay description and
        // page dots clear the system nav bar.
        val layoutDirection = LocalLayoutDirection.current
        val contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection),
            end = innerPadding.calculateEndPadding(layoutDirection),
            top = 0.dp,
            bottom = innerPadding.calculateBottomPadding(),
        )
        val moment = uiState.moment
        if (moment == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isLoading) CircularProgressIndicator(color = Color.White)
            }
        } else {
            MomentDetailContent(
                uiState = uiState,
                moment = moment,
                initialPayloadKey = uiState.initialPayloadKey,
                onAction = onAction,
                onOpenComments = { showCommentsSheet = true },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }

    val moment = uiState.moment
    if (showCommentsSheet && moment != null) {
        CommentsSheet(
            uiState = uiState,
            commentsEnabled = moment.commentsEnabled,
            onAction = onAction,
            onDismiss = { showCommentsSheet = false },
        )
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

    if (uiState.showReactionsSheet) {
        ReactionsBottomSheet(
            isLoading = uiState.isReactionsLoading,
            reactions = uiState.reactions,
            onDismiss = { onAction(MomentDetailUiAction.DismissReactionsSheet) },
        )
    }
}

@Composable
private fun MomentOverflowMenu(
    isDeleting: Boolean,
    onDeleteClick: () -> Unit,
    iconTint: Color = Color.Unspecified,
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
                    color = if (iconTint == Color.Unspecified) {
                        MaterialTheme.colorScheme.onSurface
                    } else iconTint,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(MR.string.moments_detail_menu_more),
                    tint = iconTint,
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

/**
 * "Who reacted" bottom sheet — opened from the [ReactionsRow] chip on the
 * moment detail screen. Mirrors chat's MessageInfo "Reactions" section
 * (one row per (odinId, emoji) pair) so the two surfaces stay consistent.
 * The reactor list is fetched on each open via the VM — the chip preview
 * on the detail screen already reads `moment.reactionPreview` for the
 * aggregate counts, so this sheet only pays the round-trip when the user
 * actually asks "who".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionsBottomSheet(
    isLoading: Boolean,
    reactions: List<MomentReactionUiModel>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    // Selected emoji filter. `null` means "All". Reset when the underlying
    // reactor list changes shape (e.g. a refetch dropped the active emoji)
    // so the user doesn't end up on an empty filter tab.
    var selectedEmoji by remember(reactions) { mutableStateOf<String?>(null) }

    val emojiCounts = remember(reactions) {
        reactions.groupingBy { it.emoji }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }
    val filtered = remember(reactions, selectedEmoji) {
        val emoji = selectedEmoji
        if (emoji == null) reactions else reactions.filter { it.emoji == emoji }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(MR.string.reactions),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                reactions.isEmpty() -> {
                    Text(
                        text = stringResource(MR.string.moments_detail_reactions_sheet_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 16.dp),
                    )
                }
                else -> {
                    ReactionFilterBar(
                        totalCount = reactions.size,
                        emojiCounts = emojiCounts,
                        selectedEmoji = selectedEmoji,
                        onSelect = { selectedEmoji = it },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    filtered.forEach { reaction ->
                        ReactionDetailRow(
                            reaction = reaction,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Horizontally scrolling filter bar for the reactions sheet. "All N" chip
 * first, then one chip per distinct emoji sorted by descending count so the
 * most-used reactions appear first. `null` selection == All. Built as plain
 * [FilterChip]s in a `horizontalScroll` row rather than a [androidx.compose.foundation.lazy.LazyRow] so the chip set —
 * typically 2-6 entries — measures eagerly and the sheet height settles on
 * the first frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionFilterBar(
    totalCount: Int,
    emojiCounts: List<Pair<String, Int>>,
    selectedEmoji: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selectedEmoji == null,
            onClick = { onSelect(null) },
            label = {
                Text(
                    text = stringResource(
                        MR.string.moments_detail_reactions_filter_all,
                        totalCount,
                    ),
                )
            },
        )
        emojiCounts.forEach { (emoji, count) ->
            FilterChip(
                selected = selectedEmoji == emoji,
                onClick = { onSelect(emoji) },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(emoji)
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ReactionDetailRow(
    reaction: MomentReactionUiModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PublicAvatar(
            odinId = reaction.odinId,
            initials = reaction.displayName.firstOrNull()?.toString(),
            options = AvatarOptions(size = 40.dp),
        )
        Text(
            text = reaction.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = reaction.emoji,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
private fun MomentDetailContent(
    uiState: MomentDetailUiState,
    moment: MomentFeedItem,
    initialPayloadKey: String?,
    onAction: (MomentDetailUiAction) -> Unit,
    onOpenComments: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val pageCount = moment.payloads.size.coerceAtLeast(1)

    // Share the mute toggle with the feed via the app-session singleton so a
    // single tap persists across nav-in / nav-out of the detail screen.
    val videoSession = koinInject<MomentsVideoSession>()
    val isMuted by videoSession.isMuted.collectAsStateWithLifecycle()

    // Feed → detail playback handoff. If the user tapped a video that was
    // autoplaying in the feed, MomentsVideoSession will hold the captured
    // position for one of this moment's payloads. We use that to (a) open
    // the pager on the matching payload's page and (b) seed
    // `playingPayloadKey` so the detail comes up already playing — instead
    // of the default "paused, waiting for the user to hit play."
    //
    // The actual seek to the saved position is handled inside
    // `MomentInlineVideoTile`, which reads from the same session. Here we
    // only decide which page lands and whether it auto-starts.
    val handoffPayloadKey = remember(moment.id, moment.fileId) {
        moment.payloads
            .firstOrNull { p ->
                videoSession.readPlaybackPosition(moment.fileId, p.key) != null
            }
            ?.key
    }

    // Read once per (moment.id, initialPayloadKey) — `rememberPagerState`'s
    // `initialPage` only fires on first composition, so subsequent re-emissions
    // of the same moment (e.g. a description-edit replay) won't snap the user
    // back to the seeded page. Explicit `initialPayloadKey` (e.g. from a
    // share/deeplink) wins over a handoff; otherwise the handoff page is
    // preferred over page 0.
    val initialPage = remember(moment.id, initialPayloadKey, handoffPayloadKey) {
        val seedKey = initialPayloadKey ?: handoffPayloadKey
        if (seedKey == null) 0
        else moment.payloads
            .indexOfFirst { it.key == seedKey }
            .coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pageCount },
    )

    val commentCount = uiState.comments.size

    // Per-detail-screen video play state. Unlike the feed's autoplay flow
    // there is no scroll-away to pause; the user picks play / pause via the
    // tile's centred IconButton ([MomentInlineVideoTile.showPauseAffordance]).
    // Cleared on page-change so swiping doesn't leave a video running on a
    // page that's no longer visible.
    //
    // Seeded with the feed-handoff payload key ONLY when the initial page is
    // the handoff page — otherwise opening to a photo while a different page
    // had been autoplaying in the feed would silently start that off-screen
    // video. With that gate in place, a tap-into-detail on a video resumes
    // from the saved timestamp; a tap on a still page just opens the still.
    val initialPlayingKey = remember(moment.id, initialPage, handoffPayloadKey) {
        moment.payloads.getOrNull(initialPage)?.key?.takeIf { it == handoffPayloadKey }
    }
    var playingPayloadKey by remember(moment.id) { mutableStateOf(initialPlayingKey) }
    LaunchedEffect(pagerState, moment.id) {
        // drop(1) skips snapshotFlow's initial emission of the seeded
        // currentPage — without it the collector fires on first composition
        // and immediately clears the seeded playingPayloadKey, defeating
        // the feed→detail playback handoff.
        snapshotFlow { pagerState.currentPage }.drop(1).collect { _ ->
            playingPayloadKey = null
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        // Layer 1: full-screen media pager. ContentScale.Fit (via
        // preserveAspectRatio = true) so the whole image or video is visible
        // — letterbox bars take the Box's black background. No inner
        // pointer detector so taps fall through (the surrounding sheets are
        // opened by the right-edge IconButtons below).
        if (moment.payloads.isEmpty()) {
            // Description-only moment — there's no media to render, so fill
            // with the description text centred over the black backdrop.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (moment.description.isNotBlank()) {
                    Text(
                        text = moment.description,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val payload = moment.payloads[page]
                val contentType = payload.contentType ?: ""
                val isVideo = contentType.startsWith("video/") ||
                    contentType == "application/vnd.apple.mpegurl"

                if (isVideo) {
                    // Inline-playable video tile. ButtonOnly tapMode handles
                    // the idle Play button; once playing, [useNativeControls]
                    // hands play/pause + a scrub bar over to the platform
                    // PlayerView / AVPlayerViewController, which auto-hide
                    // after a few seconds of no interaction. The Compose
                    // showPauseAffordance overlay stays off so we don't
                    // double up on pause buttons.
                    MomentInlineVideoTile(
                        payload = payload,
                        fileId = moment.fileId,
                        driveId = moment.driveId,
                        keyHeader = moment.keyHeader,
                        previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb()
                            ?: moment.previewThumbnail,
                        isUploading = false,
                        isPlaying = playingPayloadKey == payload.key,
                        onPlayTap = {
                            playingPayloadKey = if (playingPayloadKey == payload.key) {
                                null
                            } else {
                                payload.key
                            }
                        },
                        // Double-tap heart and triple-tap flame are owned by
                        // the feed card detector, not the detail screen —
                        // here a tap on the media plays/pauses.
                        onDoubleTap = {},
                        isMuted = isMuted,
                        onToggleMute = videoSession::toggleMuted,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier.fillMaxSize(),
                        tapMode = MomentVideoTapMode.ButtonOnly,
                        showPauseAffordance = false,
                        useNativeControls = true,
                    )
                } else {
                    MomentMediaItem(
                        payload = payload,
                        fileId = moment.fileId,
                        driveId = moment.driveId,
                        previewThumbnail = moment.previewThumbnail,
                        keyHeader = moment.keyHeader,
                        modifier = Modifier.fillMaxSize(),
                        imageSize = ImageSize.THUMB_XLARGE,
                        // Fit (not crop) — the whole post is visible,
                        // including landscape media in a portrait viewport
                        // (and vice versa). Letterbox bars take the Box's
                        // black background.
                        preserveAspectRatio = true,
                        messageId = moment.id,
                        shape = RectangleShape,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        // Tap is owned by the bottom-sheet trigger icons,
                        // not the media surface — keep gesture-free so
                        // horizontal swipes drive the pager cleanly.
                        onClick = null,
                    )
                }
            }
        }

        // Layer 2: right-edge action column — heart, flame, comments. Heart
        // and flame are direct toggles (no intermediate emoji sheet); their
        // counts are visible on the buttons. Long-pressing either reaction
        // button opens the existing server-loaded reactor-roster sheet so
        // the user can still see *who* reacted with what. Skip the column
        // entirely on description-only moments — nothing to overlay
        // against.
        if (moment.payloads.isNotEmpty()) {
            val heartCount = remember(moment.reactionPreview) {
                countReactionsByEmoji(moment.reactionPreview, HeartEmoji)
            }
            val flameCount = remember(moment.reactionPreview) {
                countReactionsByEmoji(moment.reactionPreview, FlameEmoji)
            }
            DetailActionColumn(
                heartCount = heartCount,
                flameCount = flameCount,
                commentCount = commentCount,
                heartActive = HeartEmoji in moment.ownReactions,
                flameActive = FlameEmoji in moment.ownReactions,
                commentsEnabled = moment.commentsEnabled,
                onToggleHeart = {
                    onAction(MomentDetailUiAction.ToggleReactionOnMoment(HeartEmoji))
                },
                onToggleFlame = {
                    onAction(MomentDetailUiAction.ToggleReactionOnMoment(FlameEmoji))
                },
                onShowReactors = {
                    onAction(MomentDetailUiAction.OpenReactionsSheet)
                },
                onOpenComments = onOpenComments,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
            )
        }

        // Layer 3: bottom overlay — page dots, description, compact
        // metadata. Translucent gradient backdrop so text reads cleanly
        // over any photo. Only shown when there's media (the description-
        // only branch above already renders the description centred).
        if (moment.payloads.isNotEmpty()) {
            DetailBottomOverlay(
                description = moment.description,
                userDateMs = moment.userDateMs,
                pageCount = if (moment.payloads.size > 1) moment.payloads.size else 0,
                currentPage = pagerState.currentPage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
    }
}

/** Vertical stack of action buttons on the right edge of the full-screen
 *  media: heart + count, flame + count, comments + count. The heart and
 *  flame are direct toggles (no intermediate emoji picker) — counts are
 *  always visible. Long-pressing either reaction button opens the existing
 *  server-loaded reactor-roster sheet so the user can still see *who*
 *  reacted with a given emoji. */
@Composable
private fun DetailActionColumn(
    heartCount: Int,
    flameCount: Int,
    commentCount: Int,
    heartActive: Boolean,
    flameActive: Boolean,
    commentsEnabled: Boolean,
    onToggleHeart: () -> Unit,
    onToggleFlame: () -> Unit,
    onShowReactors: () -> Unit,
    onOpenComments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmojiReactionButton(
            emoji = HeartEmoji,
            count = heartCount,
            isActive = heartActive,
            onClick = onToggleHeart,
            onLongPress = onShowReactors,
        )
        EmojiReactionButton(
            emoji = FlameEmoji,
            count = flameCount,
            isActive = flameActive,
            onClick = onToggleFlame,
            onLongPress = onShowReactors,
        )
        if (commentsEnabled) {
            OverlayActionButton(
                icon = Icons.AutoMirrored.Filled.Chat,
                contentDescription = stringResource(MR.string.moments_detail_comments_section),
                count = commentCount,
                onClick = onOpenComments,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiReactionButton(
    emoji: String,
    count: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    // Computed outside the Text composable so the Konsist string-literal
    // check doesn't see a Text(...) literal — even an interpolated one.
    val countLabel = remember(count) { count.toString() }
    val activeTint = if (isActive) {
        Color.White
    } else {
        Color.White.copy(alpha = 0.55f)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = if (isActive) 0.65f else 0.4f))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emoji,
                style = MaterialTheme.typography.titleMedium,
                color = activeTint,
            )
        }
        if (count > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = countLabel,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun OverlayActionButton(
    icon: ImageVector,
    contentDescription: String,
    count: Int,
    onClick: () -> Unit,
) {
    // Computed outside the Text composable so the Konsist string-literal
    // check (homebase-common's ArchitectureTest) doesn't see a Text(...)
    // literal — even an interpolated one. See CLAUDE.md.
    val countLabel = remember(count) { count.toString() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(44.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
            )
        }
        if (count > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = countLabel,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun countReactionsByEmoji(
    summary: id.homebase.api.client.drives.files.ReactionSummary?,
    emoji: String,
): Int {
    if (summary == null) return 0
    return summary.reactions.values.firstOrNull { entry ->
        runCatching {
            id.homebase.api.serialization.OdinSystemSerializer
                .deserialize<id.homebase.api.client.drives.files.reactions.ReactionContent>(entry.reactionContent)
                .emoji
        }.getOrNull() == emoji
    }?.count ?: 0
}

/** Page dots + description + date pill, with a translucent gradient
 *  backdrop so the text reads against any photo. */
@Composable
private fun DetailBottomOverlay(
    description: String,
    userDateMs: Long,
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.7f),
                    ),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pageCount > 1) {
            PagerDots(
                pageCount = pageCount,
                currentPage = currentPage,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        if (description.isNotBlank()) {
            Text(
                text = description,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        MomentDatePill(
            timestamp = kotlin.time.Instant.fromEpochMilliseconds(userDateMs),
        )
    }
}

/** Comments list + composer in a ModalBottomSheet. Replaces the
 *  always-visible LazyColumn section + sticky bottomBar of the original
 *  layout. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheet(
    uiState: MomentDetailUiState,
    commentsEnabled: Boolean,
    onAction: (MomentDetailUiAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            CommentsHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (uiState.comments.isEmpty()) {
                CommentsEmpty(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    // Service emits newest-first; reverse for chat-style
                    // chronological ordering (oldest at top, newest just
                    // above the composer).
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
            }
            if (commentsEnabled) {
                Surface(tonalElevation = 3.dp) {
                    AddCommentRow(
                        draft = uiState.commentDraft,
                        isPosting = uiState.isPostingComment,
                        onDraftChanged = { onAction(MomentDetailUiAction.CommentDraftChanged(it)) },
                        onSend = { onAction(MomentDetailUiAction.PostComment) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
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
    onShowReactors: () -> Unit,
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

        // "See who reacted" affordance — only present when there's something
        // to show. Chip-tap on the emoji chips already toggles the user's
        // own reaction, so we deliberately keep this as a separate icon
        // chip to avoid colliding with that gesture. Mirrors chat's
        // MessageInfo "Reactions" section, just opened on demand.
        if (entries.isNotEmpty()) {
            AssistChip(
                onClick = onShowReactors,
                label = {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = stringResource(MR.string.moments_detail_reactions_see_who),
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(),
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
        SharedWithDisplay.JustYou -> JustYouRow()
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
private fun JustYouRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(MR.string.moments_detail_shared_with_just_you),
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
                    text = stringResource(MR.string.moments_detail_shared_with_more, overflow),
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
    val authorLabel = if (isMine) stringResource(MR.string.moments_detail_comment_you)
    else comment.displayName
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Sender avatar — uses the same PublicAvatar widget the SharedWith
            // surface uses, so the moment detail screen has one consistent
            // identity-rendering style. Own comments fall back to the active
            // user's domain when self isn't on the conversation's contact list.
            val avatarOdinId = comment.senderOdinId
            if (avatarOdinId != null) {
                PublicAvatar(
                    odinId = avatarOdinId,
                    initials = authorLabel.firstOrNull()?.toString(),
                    options = AvatarOptions(size = 24.dp),
                )
            }
            Text(
                text = authorLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatCapturedAt(comment.userDateMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Own-comment overflow menu — Edit + Delete live here so the
            // actions cluster on the header row next to the timestamp,
            // matching MomentOverflowMenu's placement on the moment card.
            // Hidden during inline edit (the Save/Cancel row covers exit)
            // and until versionTag is confirmed (optimistic write before
            // server ack has no tag to update against).
            if (isMine && comment.versionTag != null && !isEditing) {
                CommentOverflowMenu(
                    isDeleting = isDeleting,
                    onEditClick = onEditClick,
                    onDeleteClick = onDeleteClick,
                )
            }
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
        }
    }
}

@Composable
private fun CommentOverflowMenu(
    isDeleting: Boolean,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !isDeleting) {
            if (isDeleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
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
                text = { Text(stringResource(MR.string.moments_detail_comment_edit)) },
                onClick = {
                    expanded = false
                    onEditClick()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.string.moments_detail_comment_delete)) },
                onClick = {
                    expanded = false
                    onDeleteClick()
                },
            )
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
