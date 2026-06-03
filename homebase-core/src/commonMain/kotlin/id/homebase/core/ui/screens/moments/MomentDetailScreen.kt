package id.homebase.core.ui.screens.moments

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
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
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import coil3.compose.AsyncImage
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.widget.FullScreenMediaViewer
import id.homebase.chat.widget.FullScreenVideoPlayer
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.image.ImageSize
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.moments.MomentsPreferences
import id.homebase.core.moments.MomentsViewMode
import id.homebase.core.moments.services.MomentCommentItem
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.moments.services.MomentsFeedService
import id.homebase.core.moments.services.MomentsVideoSession
import id.homebase.core.ui.screens.moments.widget.MomentDatePill
import id.homebase.core.ui.screens.moments.widget.MomentInlineVideoTile
import id.homebase.core.ui.screens.moments.widget.MomentMediaItem
import id.homebase.core.ui.screens.moments.widget.MomentVideoTapMode
import kotlin.uuid.Uuid
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
import id.homebase.resources.error_unknown
import id.homebase.resources.failed
import id.homebase.resources.file_save_failed
import id.homebase.resources.file_saved_to
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
import id.homebase.resources.moments_detail_menu_save_current
import id.homebase.resources.moments_detail_description_hint
import id.homebase.resources.moments_detail_description_save
import id.homebase.resources.moments_detail_edit_description
import id.homebase.resources.moments_detail_menu_add_people
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
import kotlinx.coroutines.launch
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
    openCommentsInitially: Boolean = false,
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
            // Reels mirrors Timeline ordering — newest posted first.
            MomentsViewMode.Timeline,
            MomentsViewMode.Reels -> feed.sortedByDescending { it.createdMs }
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
        openCommentsInitially = openCommentsInitially,
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
    // Only the initial page honours this — see [openCommentsInitially] on
    // [MomentDetailPane]. Defaults false so Reels never auto-opens comments.
    openCommentsInitially: Boolean = false,
    // Nullable so the embedded Reels view mode ([MomentsReelsView]) can run the
    // same pager with no back target — the per-page pane suppresses its back
    // arrow when this is null.
    onNavigateBack: (() -> Unit)?,
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

    // TEMP instrumentation (moments video-autoplay churn). The autoplay gate
    // below is `isActivePage = settledPage == page`; if `settledPage` flaps
    // between two adjacent pages while the pager is supposedly settled, every
    // mounted pane toggles isActivePage → tears down + rebuilds its video
    // player in a loop (see homebase_1779999024009.log). This logs the outer
    // pager's own state transitions so we can see whether settledPage is
    // oscillating and whether scroll is genuinely in progress. snapshotFlow
    // dedups by structural equality on the Triple, so it only emits on a real
    // change. Remove once the churn root cause is confirmed.
    LaunchedEffect(pagerState) {
        snapshotFlow {
            Triple(
                pagerState.currentPage,
                pagerState.settledPage,
                pagerState.isScrollInProgress,
            )
        }.collect { (current, settled, scrolling) ->
            Logger.d(tag = "MomentReels") {
                "outer pager: currentPage=$current settledPage=$settled " +
                    "scrolling=$scrolling targetPage=${pagerState.targetPage} " +
                    "offset=${pagerState.currentPageOffsetFraction} pages=${feed.size}"
            }
        }
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        // Pre-mount the moments above and below so the swipe feels instant.
        // Larger windows compound the VM-accumulation footprint above — 1 is
        // enough for the eye to never see a blank page.
        beyondViewportPageCount = 1,
        // Mirror the feed's chat-style layout (reverseLayout=true in
        // MomentsFeedList) so the visual top↔bottom orientation matches:
        // newest moment at the bottom, older moments above. Without this
        // the pager flipped the user's mental model — they'd open a
        // moment that was at the bottom of the timeline and find it at
        // the top of the pager, breaking gesture continuity (swipe up to
        // see older). With reverseLayout=true, page 0 lands at the
        // bottom and the same finger gesture (swipe up) reveals the next
        // higher-index page coming from above — same as scrolling up the
        // timeline to see older posts.
        reverseLayout = true,
        // Anchor pages to the moment IDENTITY, not the raw list index.
        // `MomentsFeedService.emitSorted` re-emits a brand-new sorted list on
        // every change (a reaction updates the moment header → BatchReceived →
        // re-emit; new posts, deletes and sync catch-up all do too — see
        // MomentsFeedService.kt:231). Without a key the pager tracks an integer
        // index, so any insert/remove/re-sort shifts the index→moment mapping
        // out from under a settled user: the moment under `currentPage` silently
        // becomes a different one, `settledPage` remaps across the mounted
        // panes, every pane's `isActivePage` flips, and the autoplay gate below
        // tears down + rebuilds its ExoPlayer in a loop (the churn the TEMP
        // instrumentation above was added to chase) — janking the Main thread so
        // a quick vertical swipe registers nothing. With a key the pager keeps
        // the same moment visible across re-emissions, so settledPage stays put
        // and the players don't churn. Keys must be saveable, so stringify the
        // Uuid (mirrors the comments LazyColumn's `key = { it.id.toString() }`).
        key = { page -> feed[page].id.toString() },
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
        // Only the settled page drives autoplay. `currentPage` flips
        // halfway through a swipe and would tear down the previous video +
        // spin up the next one mid-gesture; `settledPage` only updates when
        // the swipe finishes, so videos handoff cleanly between moments
        // without a mid-swipe load flash. Pre-mounted neighbours (the
        // beyondViewportPageCount=1 window above) get isActivePage=false
        // and stay paused.
        val isActivePage = pagerState.settledPage == page
        MomentDetailPane(
            viewModel = pageVm,
            onNavigateBack = onNavigateBack,
            isActivePage = isActivePage,
            // Only the deep-linked initial page opens straight into comments;
            // swiped-into neighbours start collapsed.
            openCommentsInitially = isInitialPage && openCommentsInitially,
        )
    }
}

/**
 * Reels view mode — the same immersive vertical-pager browse as the
 * tap-into-detail experience ([MomentDetailLoadedPager]), surfaced as a
 * standing view mode in `MomentsScreen` rather than a navigation push. Starts
 * on the newest moment and has no back target: the user leaves Reels via the
 * view-mode switcher in the Moments top bar.
 *
 * @param moments the feed already ordered by the caller — `MomentsFeedViewModel`
 *   sorts Reels like Timeline (newest posted first), so index 0 is the newest.
 */
@Composable
fun MomentsReelsView(
    moments: List<MomentFeedItem>,
    modifier: Modifier = Modifier,
) {
    val newest = moments.firstOrNull() ?: return
    Box(modifier = modifier.fillMaxSize()) {
        MomentDetailLoadedPager(
            feed = moments,
            initialMomentId = newest.id,
            initialPayloadKey = null,
            onNavigateBack = null,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MomentDetailPane(
    viewModel: MomentDetailViewModel,
    onNavigateBack: (() -> Unit)?,
    /**
     * Reels-style autoplay gate. `true` when this pane is the settled page
     * of the parent vertical pager — drives [DetailContent] to autoplay the
     * current carousel video. Defaults to `true` so embedded callers that
     * don't have a pager context (e.g. the wide-desktop side pane in
     * [MomentsScreen]) behave the same as they did before.
     */
    isActivePage: Boolean = true,
    /**
     * When true the comments sheet is expanded on first composition — used by
     * the timeline card's comment button (which navigates here with
     * `openComments = true`) so the user lands directly in the thread.
     */
    openCommentsInitially: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val fileSystemHandler = getUriHandler()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
                // "Save current": the VM has finished decrypting (+ remuxing)
                // the visible payload to a cache file; hand it to the platform
                // save-to-device flow and confirm with a snackbar. Reuses the
                // same strings + handler chat's media download uses.
                is MomentDetailUiEvent.MediaSaveReady ->
                    fileSystemHandler.saveFile(
                        file = Path(event.filePath),
                        suggestedName = event.suggestedName,
                        onSuccess = { location ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    TranslationUtil.getString(MR.string.file_saved_to, location),
                                )
                            }
                        },
                        onError = { error ->
                            scope.launch {
                                val detail = error.message
                                    ?: TranslationUtil.getString(MR.string.error_unknown)
                                snackbarHostState.showSnackbar(
                                    TranslationUtil.getString(MR.string.file_save_failed, detail),
                                )
                            }
                        },
                    )
                is MomentDetailUiEvent.MediaSaveFailed -> scope.launch {
                    val detail = event.message
                        ?: TranslationUtil.getString(MR.string.error_unknown)
                    snackbarHostState.showSnackbar(
                        TranslationUtil.getString(MR.string.file_save_failed, detail),
                    )
                }
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
                    snackbarHostState = snackbarHostState,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    isActivePage = isActivePage,
                    openCommentsInitially = openCommentsInitially,
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

/**
 * Fraction of the screen height the tapped media shrinks to (pinned at the top)
 * while the comments sheet is open. The sheet takes the remaining bottom
 * [1f - MOMENT_MEDIA_FRACTION_WITH_COMMENTS], so the two tile the screen and
 * both stay visible in one view.
 */
private const val MOMENT_MEDIA_FRACTION_WITH_COMMENTS = 1f / 3f

/**
 * Height fraction of the comments sheet while it's open. Deliberately a touch
 * under the remaining 2/3 so the gap between the shrunk media and the sheet
 * absorbs the sheet's drag handle / rounded top — otherwise the bottom of a
 * portrait clip (which fills the [MOMENT_MEDIA_FRACTION_WITH_COMMENTS] band)
 * slips behind the sheet.
 */
private const val MOMENT_COMMENTS_SHEET_FRACTION = 0.6f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailContent(
    uiState: MomentDetailUiState,
    onAction: (MomentDetailUiAction) -> Unit,
    onNavigateBack: (() -> Unit)?,
    snackbarHostState: SnackbarHostState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    isActivePage: Boolean,
    openCommentsInitially: Boolean = false,
) {
    // Local sheet toggle — pure UI state. The reactor-roster sheet
    // (uiState.showReactionsSheet) is still VM-driven below because it loads
    // avatars from the server; heart / flame counts on the action column
    // are read directly off `moment.reactionPreview` so the user doesn't
    // need to open a sheet to see them.
    //
    // Seeded from [openCommentsInitially] so a deep-link from the timeline's
    // comment button lands with the thread already open.
    var showCommentsSheet by remember { mutableStateOf(openCommentsInitially) }

    // Lifted out of [CommentsSheet] so the media-shrink animation can track the
    // sheet's *target* state instead of the post-dismissal callback.
    val commentsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Drives the media shrink. `onDismissRequest` only fires once the sheet has
    // finished sliding off-screen, so keying the shrink off `showCommentsSheet`
    // alone makes the media wait for the slide to complete before re-expanding
    // (a visible lag). Instead re-expand the moment the swipe-down starts
    // settling toward Hidden — the media then grows in parallel with the slide.
    val commentsShrinkActive by remember {
        derivedStateOf {
            showCommentsSheet && commentsSheetState.targetValue != SheetValue.Hidden
        }
    }

    // The carousel payload currently on screen, reported up from the inner
    // pager in [MomentDetailContent] so the overflow "Save current" acts on
    // exactly the photo/video the user is looking at. Null for description-only
    // moments (nothing to save → the menu item hides).
    var visiblePayloadKey by remember { mutableStateOf<String?>(null) }

    Scaffold(
        // Black so the letterbox bars around a Fit-scaled image or video
        // read as part of the cinematic surface rather than the surface
        // colour of the rest of the app.
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            isSaving = uiState.isSavingMedia,
                            // Hidden on description-only moments — nothing on
                            // screen to save.
                            showSave = visiblePayloadKey != null,
                            onSaveClick = {
                                visiblePayloadKey?.let {
                                    onAction(MomentDetailUiAction.SaveMedia(it))
                                }
                            },
                            // Author-only: only the original author can widen
                            // the audience of a moment.
                            showAddPeople = uiState.isMine,
                            onAddPeopleClick = {
                                onAction(MomentDetailUiAction.RequestAddRecipients)
                            },
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
                onVisiblePayloadChanged = { visiblePayloadKey = it },
                commentsOpen = commentsShrinkActive,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                isActivePage = isActivePage,
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
            // Media shrinks to the top 1/3 (MOMENT_MEDIA_FRACTION_WITH_COMMENTS);
            // keep the sheet a little under the remaining 2/3 so the gap absorbs
            // the sheet's drag handle / rounded top and the whole media — even a
            // portrait clip that fills the band — stays visible above it.
            heightFraction = MOMENT_COMMENTS_SHEET_FRACTION,
            // Transparent scrim so the shrunk media above the sheet stays
            // bright instead of being dimmed by the default modal scrim.
            scrimColor = Color.Transparent,
            sheetState = commentsSheetState,
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

    if (uiState.showAddRecipientsSheet && moment != null) {
        MomentAddRecipientsSheet(
            snapshot = uiState.addRecipientsSnapshot,
            query = uiState.addRecipientsQuery,
            selected = uiState.addRecipientsSelected,
            // Recipients already on the moment render locked (already-shared)
            // so the author can only widen, never remove.
            existingRecipients = moment.recipients.toSet(),
            isAdding = uiState.isAddingRecipients,
            onQueryChange = { onAction(MomentDetailUiAction.AddRecipientsQueryChanged(it)) },
            onToggle = { onAction(MomentDetailUiAction.ToggleAddRecipient(it)) },
            onConfirm = { onAction(MomentDetailUiAction.ConfirmAddRecipients) },
            onDismiss = { onAction(MomentDetailUiAction.DismissAddRecipientsSheet) },
        )
    }
}

@Composable
private fun MomentOverflowMenu(
    isDeleting: Boolean,
    isSaving: Boolean,
    showSave: Boolean,
    onSaveClick: () -> Unit,
    showAddPeople: Boolean,
    onAddPeopleClick: () -> Unit,
    onDeleteClick: () -> Unit,
    iconTint: Color = Color.Unspecified,
) {
    var expanded by remember { mutableStateOf(false) }
    // While a delete OR a save is in flight the icon is swapped for a spinner.
    // Same shape as the per-comment delete indicator: tells the user
    // "something is happening" — for save, that covers the decrypt/remux of
    // the visible payload before the device-gallery write fires.
    val busy = isDeleting || isSaving
    Box {
        IconButton(onClick = { expanded = true }, enabled = !busy) {
            if (busy) {
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
            if (showSave) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.moments_detail_menu_save_current)) },
                    onClick = {
                        expanded = false
                        onSaveClick()
                    },
                )
            }
            if (showAddPeople) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.string.moments_detail_menu_add_people)) },
                    onClick = {
                        expanded = false
                        onAddPeopleClick()
                    },
                )
            }
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
internal fun ReactionsBottomSheet(
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
    /**
     * Reports the key of the carousel payload currently on screen (null for a
     * description-only moment) so the overflow "Save current" acts on exactly
     * what the user is looking at.
     */
    onVisiblePayloadChanged: (String?) -> Unit = {},
    commentsOpen: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    isActivePage: Boolean,
    modifier: Modifier = Modifier,
) {
    val pageCount = moment.payloads.size.coerceAtLeast(1)

    // While the comments sheet is open, the media animates down to the top
    // third of the screen (top-aligned) so the tapped photo/video stays fully
    // visible above the sheet. 1f = full-screen immersive viewer (sheet closed).
    val mediaHeightFraction by animateFloatAsState(
        targetValue = if (commentsOpen) MOMENT_MEDIA_FRACTION_WITH_COMMENTS else 1f,
        label = "momentMediaShrink",
    )

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

    // Report the on-screen carousel payload up to the overflow "Save current".
    // Description-only moments have no payloads → report null so the item hides.
    // snapshotFlow already dedups, so this only fires on a real page change.
    LaunchedEffect(pagerState, moment.payloads) {
        if (moment.payloads.isEmpty()) {
            onVisiblePayloadChanged(null)
            return@LaunchedEffect
        }
        snapshotFlow { pagerState.currentPage }.collect { page ->
            onVisiblePayloadChanged(moment.payloads.getOrNull(page)?.key)
        }
    }

    val commentCount = uiState.comments.size

    // Reels-style autoplay. Two states drive `playingPayloadKey`:
    //   - This moment is the SETTLED page of the outer vertical pager
    //     (isActivePage = true) AND the current carousel page is a video →
    //     autoplay that video. The feed→detail handoff falls out for free:
    //     on first composition the inner pager is already on the handoff
    //     page, snapshotFlow's initial emission picks up its payload key,
    //     `MomentInlineVideoTile` reads MomentsVideoSession's saved
    //     position via `startPositionMs`, and playback resumes exactly
    //     where the feed left it.
    //   - Not the settled page (preloaded neighbour, mid-swipe, etc.) →
    //     null, which tears down the player so a swipe-away pauses cleanly
    //     and we don't have two videos playing at once.
    //
    // Stored as state (not derived) so the user's explicit pause via the
    // native player controls can override the autoplay — onPlayTap below
    // sets it to null and we don't fight the user by immediately
    // re-engaging on the next recomposition.
    var playingPayloadKey by remember(moment.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(isActivePage, pagerState, moment.id, moment.payloads) {
        // TEMP instrumentation (moments video-autoplay churn). Logs every
        // restart of this effect and every autoplay-key transition so we can
        // correlate `isActivePage` flips and inner-carousel page changes with
        // the player mount/teardown churn in VideoIO logs. Remove with the
        // outer-pager logging above once the root cause is confirmed.
        Logger.d(tag = "MomentReels") {
            "autoplay effect (re)start: moment=${moment.id} isActivePage=$isActivePage " +
                "innerPage=${pagerState.currentPage} payloads=${moment.payloads.size}"
        }
        if (!isActivePage) {
            if (playingPayloadKey != null) {
                Logger.d(tag = "MomentReels") {
                    "autoplay → null (inactive page): moment=${moment.id} was=$playingPayloadKey"
                }
            }
            playingPayloadKey = null
            return@LaunchedEffect
        }
        snapshotFlow {
            val payload = moment.payloads.getOrNull(pagerState.currentPage)
                ?: return@snapshotFlow null
            val ct = payload.contentType ?: ""
            val isVideo = ct.startsWith("video/") ||
                ct == "application/vnd.apple.mpegurl"
            if (isVideo) payload.key else null
        }.collect { autoplayKey ->
            if (autoplayKey != playingPayloadKey) {
                Logger.d(tag = "MomentReels") {
                    "autoplay key change: moment=${moment.id} innerPage=${pagerState.currentPage} " +
                        "from=$playingPayloadKey to=$autoplayKey"
                }
            }
            playingPayloadKey = autoplayKey
        }
    }

    // Same animation as the feed card's double/triple-tap reaction. The
    // detail screen's heart and flame buttons feed this controller so the
    // visual confirmation is identical across surfaces.
    val floatingController = rememberFloatingReactionController()

    Box(modifier = modifier.background(Color.Black)) {
        // Layer 1: full-screen media pager. ContentScale.Fit (via
        // preserveAspectRatio = true) so the whole image or video is visible
        // — letterbox bars take the Box's black background. No inner
        // pointer detector so taps fall through (the surrounding sheets are
        // opened by the right-edge IconButtons below).
        if (moment.payloads.isEmpty()) {
            // Either a just-posted moment whose payloads haven't landed yet, or
            // a genuine description-only moment. A tap anywhere opens the detail
            // panel (comments + description + edit).
            val pendingPreview = uiState.pendingLocalPreviewModel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(mediaHeightFraction)
                    .align(Alignment.TopCenter)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenComments,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (pendingPreview != null) {
                    // Placeholder window after posting: show the picked media
                    // (a video's poster bytes / a photo's path) with a spinner,
                    // mirroring the timeline card — otherwise this branch is a
                    // bare black backdrop until real payloads arrive.
                    AsyncImage(
                        model = pendingPreview,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    CircularProgressIndicator(color = Color.White)
                } else if (moment.description.isNotBlank()) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(mediaHeightFraction)
                    .align(Alignment.TopCenter),
                // This pager fills the whole page (mediaHeightFraction = 1f when
                // comments are closed), so it sits under every swipe. A
                // single-payload moment has nowhere to scroll horizontally, but
                // an *enabled* horizontal Pager still installs a live orientation
                // -locked `scrollable` that competes with the parent
                // VerticalPager for the gesture: a slightly-diagonal swipe can
                // cross horizontal touch-slop first and get claimed here, so the
                // vertical swipe drops. Gate scrolling on having more than one
                // page so single-payload moments (the common case) leave the
                // vertical gesture entirely to the parent. Real carousels keep
                // horizontal paging.
                userScrollEnabled = pageCount > 1,
            ) { page ->
                val payload = moment.payloads[page]
                val contentType = payload.contentType ?: ""
                val isVideo = contentType.startsWith("video/") ||
                    contentType == "application/vnd.apple.mpegurl"

                // Single tap anywhere on the media opens the detail panel
                // (comments + description + edit). The reaction column, mute
                // button and the video's centred play/pause affordance are
                // children drawn over this surface, so they intercept their
                // own taps first; everything else falls through to here.
                // `clickable` doesn't consume horizontal drags, so the parent
                // HorizontalPager still swipes between carousel items cleanly.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenComments,
                        ),
                ) {
                    if (isVideo) {
                        // Inline-playable video tile. ButtonOnly tapMode skips
                        // the full-surface tap detector so the tap above opens
                        // the panel; a centred play/pause affordance
                        // ([showPauseAffordance]) keeps manual playback control
                        // without a native-controls layer that would swallow
                        // the panel tap. Autoplay + the mute button remain.
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
                            // the feed card detector, not the detail screen.
                            onDoubleTap = {},
                            isMuted = isMuted,
                            onToggleMute = videoSession::toggleMuted,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            modifier = Modifier.fillMaxSize(),
                            tapMode = MomentVideoTapMode.ButtonOnly,
                            showPauseAffordance = true,
                            useNativeControls = false,
                            // While the comments sheet is open the media is
                            // shrunk to the top band — show the whole frame
                            // (fit) instead of the immersive crop-to-fill.
                            fitToContent = commentsOpen,
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
                            // While the comments sheet is open the pager is
                            // height-constrained to the top band; fill that box
                            // (Fit) instead of letting the image's own aspect
                            // ratio size it and overflow the band — mirrors the
                            // video tile's fitToContent here.
                            fitBounds = commentsOpen,
                            messageId = moment.id,
                            shape = RectangleShape,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            // Tap handled by the wrapping Box above (opens the
                            // panel); keep the image itself gesture-free so the
                            // horizontal swipe drives the pager cleanly.
                            onClick = null,
                        )
                    }
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
        // Hidden while the comments sheet is open — the column sits at
        // CenterStart of the full screen, which would otherwise float over the
        // sheet once the media has shrunk to the top third.
        if (moment.payloads.isNotEmpty() && !commentsOpen) {
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
                    // Check ownReactions BEFORE the toggle dispatch so we
                    // know whether to play the add or remove animation. The
                    // dispatch is async (optimistic write hits the store
                    // first, but the uiState combine has its own latency),
                    // so reading post-dispatch could race.
                    val isRemoving = HeartEmoji in moment.ownReactions
                    floatingController.show(HeartEmoji, isRemoving)
                    onAction(MomentDetailUiAction.ToggleReactionOnMoment(HeartEmoji))
                },
                onToggleFlame = {
                    val isRemoving = FlameEmoji in moment.ownReactions
                    floatingController.show(FlameEmoji, isRemoving)
                    onAction(MomentDetailUiAction.ToggleReactionOnMoment(FlameEmoji))
                },
                onShowReactors = {
                    onAction(MomentDetailUiAction.OpenReactionsSheet)
                },
                onOpenComments = onOpenComments,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp),
            )
        }

        // Layer 3: bottom overlay — page dots, description, compact
        // metadata. Translucent gradient backdrop so text reads cleanly
        // over any photo. Only shown when there's media (the description-
        // only branch above already renders the description centred).
        // Hidden while the comments sheet is open — the description and capture
        // date are shown inside the sheet, and this bottom overlay would land
        // behind / over the sheet anyway.
        if (moment.payloads.isNotEmpty() && !commentsOpen) {
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

        // Layer 4: floating reaction confirmation — same animation the feed
        // card uses for double/triple-tap reactions. Drawn last so it lands
        // on top of media + action column + bottom overlay. Suppressed while
        // the comments sheet is open (reactions aren't reachable then, and a
        // screen-centred animation would land over the sheet).
        if (!commentsOpen) {
            FloatingReactionOverlay(
                display = floatingController.display,
                modifier = Modifier.align(Alignment.Center),
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
internal fun EmojiReactionButton(
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

internal fun countReactionsByEmoji(
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
    // When set, the sheet content is pinned to this fraction of the available
    // height so the surface behind it stays partly visible (timeline use — the
    // tapped moment peeks above the sheet). Null sizes to content and grows up
    // to full height (detail use, over the black immersive viewer).
    heightFraction: Float? = null,
    // Overrides the modal scrim. The detail screen passes Color.Transparent so
    // the media it shrinks to the top third above the sheet stays bright;
    // other callers keep the default dimming scrim.
    scrimColor: Color? = null,
    // Lifted in by the detail screen so it can observe the sheet's target state
    // and re-expand the shrunk media in parallel with the slide-down. Defaults
    // to a locally-remembered state for callers that don't need that coupling.
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = scrimColor ?: BottomSheetDefaults.ScrimColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (heightFraction != null) Modifier.fillMaxHeight(heightFraction)
                    else Modifier,
                ),
        ) {
            // Single scroll container for the whole sheet body: the description,
            // the (expandable) "Shared with" metadata, and the comments thread all
            // live inside one LazyColumn. Expanding the recipient list then grows
            // the scroll content instead of overflowing a static header — the
            // earlier layout pinned those sections above the comments LazyColumn,
            // so expansion pushed content off-screen with no way to scroll it back.
            // Only the composer below stays pinned.
            LazyColumn(
                // Fill the middle on a fixed-height sheet (composer pinned at the
                // bottom); on the content-sized detail sheet, wrap so the sheet
                // only grows as tall as the content needs.
                modifier = Modifier.weight(1f, fill = heightFraction != null),
            ) {
                item(key = "description") {
                    MomentDescriptionSection(
                        description = uiState.moment?.description.orEmpty(),
                        isMine = uiState.isMine,
                        isEditing = uiState.isEditingDescription,
                        draft = uiState.descriptionDraft,
                        isSaving = uiState.isSavingDescription,
                        onStartEdit = { onAction(MomentDetailUiAction.StartEditDescription) },
                        onDraftChanged = { onAction(MomentDetailUiAction.DescriptionDraftChanged(it)) },
                        onSave = { onAction(MomentDetailUiAction.SaveDescriptionEdit) },
                        onCancel = { onAction(MomentDetailUiAction.CancelDescriptionEdit) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                // Capture time + "Shared with" recipients. Lives here (the info/
                // comments sheet) because the immersive media view has no room for
                // an expandable, network-backed recipient list. Expanding the
                // "Shared with" row lazily fetches transfer history via the VM.
                item(key = "metadata") {
                    MetadataSection(
                        capturedAtMs = uiState.moment?.userDateMs ?: 0L,
                        sharedWith = uiState.sharedWith,
                        sharedWithExpanded = uiState.sharedWithExpanded,
                        onToggleSharedWith = {
                            onAction(MomentDetailUiAction.ToggleSharedWithExpansion(it))
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
                item(key = "divider") { HorizontalDivider() }
                item(key = "comments-header") {
                    CommentsHeader(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (uiState.comments.isEmpty()) {
                    item(key = "comments-empty") {
                        CommentsEmpty(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    }
                } else {
                    // Service emits newest-first; reverse for chat-style
                    // chronological ordering (oldest at top, newest just
                    // above the composer).
                    items(uiState.comments.asReversed(), key = { it.id.toString() }) { comment ->
                        CommentRowFromState(
                            uiState = uiState,
                            comment = comment,
                            onAction = onAction,
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

/**
 * A single [CommentRow] wired from [MomentDetailUiState] — derives the
 * "is mine" / editing / deleting flags and forwards the per-comment actions.
 * Extracted so the detail [CommentsSheet] (also reused by the timeline's
 * [MomentCommentsSheet]) renders identical rows from one place.
 */
@Composable
private fun CommentRowFromState(
    uiState: MomentDetailUiState,
    comment: MomentCommentItem,
    onAction: (MomentDetailUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        onDeleteClick = { onAction(MomentDetailUiAction.RequestDeleteComment(comment.id)) },
        onToggleReaction = { emoji ->
            onAction(MomentDetailUiAction.ToggleReactionOnComment(comment.id, emoji))
        },
        modifier = modifier,
    )
}

/**
 * Modal comments + description sheet for a moment on the compact timeline (see
 * [MomentsScreen]). Wraps the same [CommentsSheet] the full-screen detail view
 * uses — so both surfaces show an identical thread — and adds the
 * comment-delete confirmation dialog (the detail screen wires that itself in
 * `DetailContent`, which this entry point doesn't go through).
 *
 * As a `ModalBottomSheet`, [CommentsSheet] brings its own scrim and blocks the
 * feed behind it until the user swipes it down or taps the scrim, so the thread
 * stays locked to the moment it was opened from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MomentCommentsSheet(
    uiState: MomentDetailUiState,
    onAction: (MomentDetailUiAction) -> Unit,
    onDismiss: () -> Unit,
    // Lifted in by the timeline feed list so it can observe the sheet's target
    // state and drop the shrunk media band in parallel with the slide-down.
    // Defaults to a locally-remembered state for any other caller.
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    CommentsSheet(
        uiState = uiState,
        // Until the moment resolves from the feed, assume comments are allowed
        // so the composer doesn't flash hidden then in on first frame.
        commentsEnabled = uiState.moment?.commentsEnabled ?: true,
        onAction = onAction,
        onDismiss = onDismiss,
        // Leave the top third showing the tapped moment (MomentsScreen shrinks
        // it to a band above the sheet).
        heightFraction = 0.65f,
        // Transparent scrim so the shrunk media band above the sheet stays
        // bright instead of being dimmed by the default modal scrim.
        scrimColor = Color.Transparent,
        sheetState = sheetState,
    )

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

/**
 * Description block at the top of the detail panel. Read-only for receivers;
 * the author ([isMine]) gets a pencil affordance that swaps the text for an
 * inline editor wired to the moment-description update path
 * ([MomentsPostSenderService.updateMoment] via the VM). Save/Cancel commit or
 * discard the [draft].
 */
@Composable
private fun MomentDescriptionSection(
    description: String,
    isMine: Boolean,
    isEditing: Boolean,
    draft: String,
    isSaving: Boolean,
    onStartEdit: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isEditing) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                placeholder = { Text(stringResource(MR.string.moments_detail_description_hint)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel, enabled = !isSaving) {
                    Text(stringResource(MR.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onSave, enabled = !isSaving) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(MR.string.moments_detail_description_save))
                    }
                }
            }
        } else {
            val hasDescription = description.isNotBlank()
            // Built outside Text() so the Konsist string-literal check sees a
            // variable, not a literal. See CLAUDE.md.
            val descriptionText = if (hasDescription) {
                description
            } else {
                stringResource(MR.string.moments_detail_no_description)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = descriptionText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasDescription) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp),
                )
                if (isMine) {
                    IconButton(onClick = onStartEdit) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(
                                MR.string.moments_detail_edit_description,
                            ),
                        )
                    }
                }
            }
        }
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
