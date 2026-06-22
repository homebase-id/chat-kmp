package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.core.feed.services.ChannelDefinition
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.ui.screens.feed.widget.CommentsModalSheet
import id.homebase.core.ui.screens.feed.widget.PostCard
import id.homebase.resources.MR
import id.homebase.resources.feed_timeline_compose_action
import id.homebase.resources.feed_timeline_empty_action
import id.homebase.resources.feed_timeline_empty_body
import id.homebase.resources.feed_timeline_empty_title
import id.homebase.resources.feed_timeline_error_retry
import id.homebase.resources.feed_timeline_error_title
import id.homebase.resources.feed_timeline_following_action
import id.homebase.resources.feed_timeline_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

/**
 * Native home timeline. Lists [PostCard]s newest-first with infinite scroll,
 * pull-to-refresh, and empty / loading / error states.
 *
 * Navigation is callback-based: the VM emits one-time [FeedTimelineEvent]s collected
 * here and forwarded to [onNavigateToDetail] / [onNavigateToComposer]; the screen never
 * holds a NavController.
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
    onNavigateToComposer: () -> Unit,
    onRepost: (FeedPostItem) -> Unit,
    onNavigateToFollowing: () -> Unit,
    onAuthorClick: (OdinId) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Collected so the list recomposes (and channel labels appear) once definitions load.
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // Tapping a post or its comment button opens comments as a bottom-sheet modal over the feed
    // (vs navigating away); null == closed. Reactors / media still route to the detail screen.
    var commentsPostId by remember { mutableStateOf<Uuid?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FeedTimelineEvent.NavigateToDetail -> onNavigateToDetail(event.postId)
                FeedTimelineEvent.NavigateToComposer -> onNavigateToComposer()
                is FeedTimelineEvent.ShowSnackbar ->
                    snackbarHostState.showSnackbar(TranslationUtil.getString(event.messageKey))
            }
        }
    }

    Scaffold(
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
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::onComposeClick,
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(MR.string.feed_timeline_compose_action),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)

        when {
            uiState.errorMessage != null -> FeedTimelineError(
                message = uiState.errorMessage!!,
                onRetry = viewModel::refresh,
                modifier = contentModifier,
            )

            uiState.isLoading && uiState.posts.isEmpty() -> FeedTimelineLoading(
                modifier = contentModifier,
            )

            uiState.posts.isEmpty() -> FeedTimelineEmpty(
                onCompose = viewModel::onComposeClick,
                modifier = contentModifier,
            )

            else -> FeedTimelineList(
                uiState = uiState,
                onRefresh = viewModel::refresh,
                onLoadMore = viewModel::loadMore,
                onPostClick = viewModel::onPostClick,
                onOpenComments = { commentsPostId = it },
                onToggleReaction = viewModel::onToggleReaction,
                onRepost = onRepost,
                onAuthorClick = onAuthorClick,
                channels = channels,
                channelNameFor = viewModel::channelNameFor,
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
}

/** Number of items from the end at which [onLoadMore] is triggered. */
private const val LOAD_MORE_THRESHOLD = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedTimelineList(
    uiState: FeedTimelineUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPostClick: (Uuid) -> Unit,
    onOpenComments: (Uuid) -> Unit,
    onToggleReaction: (post: FeedPostItem, emoji: String) -> Unit,
    onRepost: (FeedPostItem) -> Unit,
    onAuthorClick: (OdinId) -> Unit,
    channels: Map<String, ChannelDefinition>,
    channelNameFor: (String) -> String?,
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
        LazyColumn(
            state = listState,
            // Posts are flat bands on `surface`; the list paints a slightly-darker
            // surfaceContainerLowest behind them so the 8dp gaps read as separators.
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.posts, key = { it.id.toString() }) { post ->
                // Read `channels` so the row recomposes when definitions arrive; the lambda
                // returns null for public/unknown channels.
                channels
                PostCard(
                    post = post,
                    displayName = (post.originalAuthor ?: post.senderOdinId)?.domainName.orEmpty(),
                    channelName = channelNameFor(post.channelId),
                    onToggleReaction = { emoji -> onToggleReaction(post, emoji) },
                    onRepost = { onRepost(post) },
                    onOpenComments = { onOpenComments(post.id) },
                    onShowReactors = { onPostClick(post.id) },
                    onPostClick = { onOpenComments(post.id) },
                    onMediaClick = { onPostClick(post.id) },
                    onAuthorClick = {
                        val author = post.originalAuthor ?: post.senderOdinId
                        if (author != null) onAuthorClick(author)
                    },
                )
            }
        }
    }
}

/**
 * Emits whether the list is scrolled within [LOAD_MORE_THRESHOLD] of the end. Kept as a
 * thin wrapper so the screen body stays readable; the Boolean key it returns is what
 * `distinctUntilChanged` dedups on.
 */
private fun snapshotFlowShouldLoadMore(
    listState: LazyListState,
    itemCount: Int,
) = snapshotFlow {
    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    itemCount > 0 && lastVisible >= itemCount - 1 - LOAD_MORE_THRESHOLD
}

@Composable
private fun FeedTimelineLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FeedTimelineEmpty(
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.DynamicFeed,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(MR.string.feed_timeline_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(MR.string.feed_timeline_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onCompose) {
            Text(stringResource(MR.string.feed_timeline_empty_action))
        }
    }
}

@Composable
private fun FeedTimelineError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(MR.string.feed_timeline_error_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onRetry) {
            Text(stringResource(MR.string.feed_timeline_error_retry))
        }
    }
}
