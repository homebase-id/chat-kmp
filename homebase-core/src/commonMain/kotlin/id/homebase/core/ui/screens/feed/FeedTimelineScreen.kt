package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.core.feed.services.ChannelDefinition
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.PostType
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.ReactionsBottomSheet
import id.homebase.core.ui.screens.feed.widget.CommentsModalSheet
import id.homebase.core.ui.screens.feed.widget.FEED_SKELETON_COUNT
import id.homebase.core.ui.screens.feed.widget.FeedMessageState
import id.homebase.core.ui.screens.feed.widget.PostCard
import id.homebase.core.ui.screens.feed.widget.PostSkeleton
import id.homebase.resources.MR
import id.homebase.resources.feed_timeline_empty_body
import id.homebase.resources.feed_timeline_empty_title
import id.homebase.resources.feed_timeline_error_body
import id.homebase.resources.feed_timeline_error_retry
import id.homebase.resources.feed_timeline_error_title
import id.homebase.resources.feed_timeline_following_action
import id.homebase.resources.feed_timeline_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

/**
 * Native home timeline. Lists [PostCard]s newest-first with infinite scroll,
 * pull-to-refresh, and skeleton / empty / error states.
 *
 * Navigation is callback-based: the VM emits one-time [FeedTimelineEvent]s collected
 * here and forwarded to [onNavigateToDetail]; the screen never holds a NavController.
 *
 * @param onAuthorClick opens the tapped post author's profile. [PostCard]'s own
 *   `onAuthorClick` takes no arg, so the author identity (`originalAuthor ?: senderOdinId`)
 *   is resolved here per row before invoking this callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedTimelineScreen(
    viewModel: FeedTimelineViewModel = koinViewModel(),
    onNavigateToDetail: (Uuid) -> Unit,
    onNavigateToFollowing: () -> Unit,
    onAuthorClick: (OdinId) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Opens the author's report intake when a viewer reports someone else's post (web parity).
    val uriHandler = getUriHandler()
    // Collected so the list recomposes (and channel labels appear) once definitions load.
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    // Resolved author names (saved contacts + connections); the row falls back to the raw
    // domain for identities absent here, mirroring the web feed's AuthorName.
    val displayNames by viewModel.displayNames.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // The LargeTopAppBar collapses to a small bar as the list scrolls up; exitUntilCollapsed
    // gives the IG/FB "big title shrinks then pins" motion.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(
        state = rememberTopAppBarState(),
    )
    // Tapping a post or its comment button opens comments as a bottom-sheet modal over the feed
    // (vs navigating away); null == closed. Reactors / media still route to the detail screen.
    var commentsPostId by remember { mutableStateOf<Uuid?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FeedTimelineEvent.NavigateToDetail -> onNavigateToDetail(event.postId)
                is FeedTimelineEvent.ShowSnackbar ->
                    snackbarHostState.showSnackbar(TranslationUtil.getString(event.messageKey))
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.feed_timeline_title)) },
                actions = {
                    IconButton(onClick = onNavigateToFollowing) {
                        Icon(
                            imageVector = Icons.Outlined.Group,
                            contentDescription = stringResource(
                                MR.string.feed_timeline_following_action,
                            ),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)

        when {
            uiState.errorMessage != null -> FeedMessageState(
                icon = Icons.Outlined.CloudOff,
                iconContentDescription = null,
                title = stringResource(MR.string.feed_timeline_error_title),
                body = stringResource(MR.string.feed_timeline_error_body),
                actionLabel = stringResource(MR.string.feed_timeline_error_retry),
                onAction = viewModel::refresh,
                modifier = contentModifier,
            )

            uiState.isLoading && uiState.posts.isEmpty() -> FeedTimelineLoading(
                modifier = contentModifier,
            )

            uiState.posts.isEmpty() -> FeedMessageState(
                icon = Icons.Outlined.DynamicFeed,
                iconContentDescription = null,
                title = stringResource(MR.string.feed_timeline_empty_title),
                body = stringResource(MR.string.feed_timeline_empty_body),
                modifier = contentModifier,
            )

            else -> FeedTimelineList(
                uiState = uiState,
                onRefresh = viewModel::refresh,
                onLoadMore = viewModel::loadMore,
                onPostClick = viewModel::onPostClick,
                onOpenComments = { commentsPostId = it },
                onShowReactors = viewModel::showReactors,
                onToggleReaction = viewModel::onToggleReaction,
                onAuthorClick = onAuthorClick,
                onDeletePost = viewModel::deletePost,
                onReportPost = { uriHandler.openUrl(FEED_REPORT_URL) },
                selfOdinId = uiState.selfOdinId,
                channels = channels,
                channelNameFor = viewModel::channelNameFor,
                isPublicChannel = viewModel::isPublicChannel,
                displayNames = displayNames,
                modifier = contentModifier,
            )
        }
    }

    commentsPostId?.let { pid ->
        CommentsModalSheet(
            postId = pid,
            onDismiss = { commentsPostId = null },
            onAuthorClick = onAuthorClick,
        )
    }

    // Inline "who reacted" sheet for tweet/media posts (articles use the detail screen). Names
    // fall back to the reactor's domain; the avatar is derived from the odinId inside the sheet.
    uiState.reactorsSheet?.let { reactors ->
        ReactionsBottomSheet(
            reactions = reactors,
            isLoading = uiState.isReactorsLoading,
            ownerOdinId = uiState.selfOdinId?.domainName,
            onContactClick = { onAuthorClick(OdinId(it)) },
            onDismiss = viewModel::dismissReactors,
        )
    }
}

/** Number of items from the end at which [onLoadMore] is triggered. */
private const val LOAD_MORE_THRESHOLD = 4

// ponytail: on wide (tablet/desktop) windows the feed column is capped to 80% width and centered
// so posts don't stretch full-bleed; phone-width (< 600dp, the M3 compact/medium breakpoint) stays
// full width. Bump FEED_WIDE_FRACTION down (or swap for a widthIn max) if 80% still reads too wide.
private val FEED_WIDE_BREAKPOINT = 600.dp
private const val FEED_WIDE_FRACTION = 0.8f

/**
 * Paints the darker feed band across the full width, then hands [content] a modifier that is
 * centered and capped at [FEED_WIDE_FRACTION] on wide windows / full width on phones. Shared by the
 * real list and the loading skeletons so both track the same column width.
 */
@Composable
private fun FeedWidthContainer(
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val fraction = if (maxWidth >= FEED_WIDE_BREAKPOINT) FEED_WIDE_FRACTION else 1f
        content(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .align(Alignment.TopCenter),
        )
    }
}

// Reporting intake opened by the post overflow "Report" action on someone else's post.
// ponytail: the documented web fallback URL; the per-identity `{author}/config/reporting` lookup
// is the refinement (it needs an HTTP client wired into the feed layer — not worth it yet).
private const val FEED_REPORT_URL = "https://ravenhosting.cloud/report"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedTimelineList(
    uiState: FeedTimelineUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPostClick: (Uuid) -> Unit,
    onOpenComments: (Uuid) -> Unit,
    onShowReactors: (FeedPostItem) -> Unit,
    onToggleReaction: (post: FeedPostItem, emoji: String) -> Unit,
    onAuthorClick: (OdinId) -> Unit,
    onDeletePost: (FeedPostItem) -> Unit,
    onReportPost: (FeedPostItem) -> Unit,
    selfOdinId: OdinId?,
    channels: Map<String, ChannelDefinition>,
    channelNameFor: (String) -> String?,
    isPublicChannel: (String) -> Boolean,
    displayNames: Map<OdinId, String>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    // Trigger loadMore as the user nears the end. The snapshotFlow body returns a coarse
    // Boolean "near the end" key, which snapshotFlow already self-dedups structurally before
    // emitting — so no trailing distinctUntilChanged (that would run the same Boolean compare
    // twice; see CLAUDE.md).
    LaunchedEffect(listState, uiState.endReached, uiState.posts.size) {
        if (uiState.endReached) return@LaunchedEffect
        snapshotFlowShouldLoadMore(listState, uiState.posts.size)
            .collect { shouldLoad -> if (shouldLoad) onLoadMore() }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
      // The darker surfaceContainerHigh band fills the full width (painted by FeedWidthContainer);
      // the post column itself is centered and width-capped on wide windows so the gaps between
      // posts and the side margins read as the same separator colour.
      FeedWidthContainer { columnModifier ->
        LazyColumn(
            state = listState,
            modifier = columnModifier,
            contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(uiState.posts, key = { it.id.toString() }) { post ->
                // Read `channels` so the row recomposes when definitions arrive; the lambda
                // returns null for public/unknown channels.
                channels
                val author = post.originalAuthor ?: post.senderOdinId
                // The full PostDetail screen is reserved for long-form Articles. A tweet/media
                // post stays inline: comments open as a modal sheet, the reaction facepile opens
                // the inline reactors sheet, and tapping media is a no-op (double-tap still likes;
                // the fullscreen lightbox is deferred until the over-peer media route lands).
                val isArticle = post.type == PostType.Article
                // originalAuthor survives the server stripping senderOdinId, so it's the reliable
                // own-vs-other signal for the overflow menu (Edit/Delete vs Report).
                val isOwnPost = selfOdinId != null && author == selfOdinId
                PostCard(
                    post = post,
                    displayName = author
                    ?.let { displayNames[it]?.takeIf { n -> n.isNotBlank() } ?: it.domainName }
                    .orEmpty(),
                    channelName = channelNameFor(post.channelId),
                    isPublic = isPublicChannel(post.channelId),
                    onToggleReaction = { emoji -> onToggleReaction(post, emoji) },
                    // ponytail: post composer disabled for now (PR #802). PostCard hides the
                    // repost button + overflow Edit item when these are null; Delete/Report stay.
                    // Restore the FAB + Route.PostCompose (see AppNavHost) to re-enable compose.
                    onRepost = null,
                    onOpenComments = { onOpenComments(post.id) },
                    onShowReactors = { if (isArticle) onPostClick(post.id) else onShowReactors(post) },
                    onPostClick = { onOpenComments(post.id) },
                    onMediaClick = { if (isArticle) onPostClick(post.id) },
                    onAuthorClick = { if (author != null) onAuthorClick(author) },
                    embeddedAuthorName = post.embeddedPost?.author
                        ?.let { displayNames[OdinId(it)]?.takeIf { n -> n.isNotBlank() } },
                    isOwnPost = isOwnPost,
                    onEditPost = null,
                    onDeletePost = { onDeletePost(post) },
                    onReportPost = { onReportPost(post) },
                )
            }
        }
      }
    }
}

/**
 * Emits whether the list is scrolled within [LOAD_MORE_THRESHOLD] of the end. Kept as a
 * thin wrapper so the screen body stays readable; the Boolean key it returns is what
 * snapshotFlow dedups on.
 */
private fun snapshotFlowShouldLoadMore(
    listState: LazyListState,
    itemCount: Int,
) = snapshotFlow {
    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    itemCount > 0 && lastVisible >= itemCount - 1 - LOAD_MORE_THRESHOLD
}

/**
 * The cold-start loading state: a stack of shimmering [PostSkeleton]s on the same darker band
 * background as the real list, so the load reads as "posts arriving" rather than a bare spinner.
 */
@Composable
private fun FeedTimelineLoading(modifier: Modifier = Modifier) {
    FeedWidthContainer(modifier) { columnModifier ->
        LazyColumn(
            modifier = columnModifier,
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            userScrollEnabled = false,
        ) {
            items(FEED_SKELETON_COUNT) {
                PostSkeleton()
            }
        }
    }
}
