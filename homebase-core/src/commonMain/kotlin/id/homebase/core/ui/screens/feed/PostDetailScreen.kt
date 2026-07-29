package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.core.feed.services.CanReact
import id.homebase.core.feed.services.ChannelDefinitionService
import id.homebase.core.feed.services.DenyReason
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.feed.services.authorOdinId
import id.homebase.core.feed.services.isAuthoredBy
import id.homebase.core.util.buildBlockUrl
import id.homebase.core.util.getUriHandler
import id.homebase.core.ui.screens.feed.widget.CommentComposer
import id.homebase.core.ui.screens.feed.widget.CommentThread
import id.homebase.core.ui.screens.feed.widget.FeedMediaFullScreenHost
import id.homebase.core.ui.screens.feed.widget.PostCard
import id.homebase.core.ui.screens.feed.widget.feedMediaOverlay
import id.homebase.core.widget.ReactionsBottomSheet
import id.homebase.resources.MR
import id.homebase.resources.feed_comment_denied_anonymous
import id.homebase.resources.feed_comment_denied_no_access
import id.homebase.resources.feed_comment_denied_unknown
import id.homebase.resources.feed_post_block
import id.homebase.resources.feed_post_detail_comments_disabled
import id.homebase.resources.feed_post_report
import id.homebase.resources.feed_reactors_partial
import id.homebase.resources.feed_post_detail_delete
import id.homebase.resources.feed_post_detail_more_actions
import id.homebase.resources.feed_post_detail_not_found
import id.homebase.resources.feed_post_detail_title
import id.homebase.resources.menu_back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Post detail + comments. Resolves the post from the live timeline via [PostDetailViewModel],
 * renders the full [PostCard] at the top, the [CommentThread] below, and a pinned
 * [CommentComposer] at the bottom that shows the reply target when one is active. A post that
 * disallows comments still shows the ones it has — only the composer goes away.
 *
 * Navigation is callback-driven: the VM emits one-time [PostDetailEvent]s that this screen
 * collects and forwards to [onBack] / [onAuthorClick]; the screen never holds a NavController.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(
    viewModel: PostDetailViewModel = koinViewModel(),
    onBack: () -> Unit,
    onAuthorClick: (OdinId) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val channelService = koinInject<ChannelDefinitionService>()
    val channels by channelService.channels.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uriHandler = getUriHandler()
    // Tapped photo/video, shown full-screen over the post; null == closed. Pure view state.
    var overlay by remember { mutableStateOf<FullScreenOverlay?>(null) }
    // Hoisted above [FeedMediaFullScreenHost]: opening the viewer swaps this screen out of the
    // composition, so a state remembered inside it would come back scrolled to the top.
    val postScrollState = rememberScrollState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PostDetailEvent.NavigateBack -> onBack()
                is PostDetailEvent.NavigateToAuthor -> onAuthorClick(event.odinId)
                is PostDetailEvent.OpenUrl -> uriHandler.openUrl(event.url)
                is PostDetailEvent.ShowSnackbar -> {
                    val message = event.message ?: return@collect
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            }
        }
    }

    val post = uiState.post
    // Two independent gates, as on the web: the post says whether it accepts comments at all,
    // the channel's drive grants say whether THIS viewer may write one.
    val postAllowsComment = post != null &&
        (post.reactAccess == ReactAccess.All || post.reactAccess == ReactAccess.CommentOnly)
    // Permissive while the verdict is still resolving (`canReact == null`), matching the
    // comments sheet: showing the composer and hiding it a beat later reads worse than the
    // rare case of a denied user seeing it briefly, and `commentDenial` stays quiet until then.
    val canComment = postAllowsComment && uiState.canReact?.allowsComment != false
    val commentDenial = commentDenial(post != null, postAllowsComment, uiState.canReact)

    // Resolve an author's display name from the contact/connection map the VM streams,
    // falling back to the raw domain for identities we don't know (web `AuthorName` parity).
    val displayNameFor: (OdinId?) -> String = { odinId ->
        odinId?.let { id -> uiState.displayNames[id]?.takeIf { it.isNotBlank() } }
            ?: odinId?.domainName.orEmpty()
    }

    FeedMediaFullScreenHost(overlay = overlay, onDismiss = { overlay = null }) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                var menuOpen by remember { mutableStateOf(false) }
                TopAppBar(
                    title = { Text(stringResource(MR.string.feed_post_detail_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.string.menu_back),
                            )
                        }
                    },
                    actions = {
                        // Owner gets Delete; everyone else gets the web's external actions
                        // (Report / Block). originalAuthor survives the server stripping senderOdinId.
                        val isMyPost = post?.isAuthoredBy(uiState.selfOdinId) == true
                        val postAuthor = post?.authorOdinId
                        if (post != null) {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = stringResource(
                                        MR.string.feed_post_detail_more_actions,
                                    ),
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                if (isMyPost) {
                                    // ponytail: post composer disabled for now (PR #802) — the owner
                                    // Edit item is gone; Delete stays. Restore it + Route.PostCompose
                                    // to re-enable.
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(MR.string.feed_post_detail_delete))
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Delete,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.deletePost()
                                        },
                                    )
                                } else if (postAuthor != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(MR.string.feed_post_report)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Outlined.Flag,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.reportPost()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(MR.string.feed_post_block)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Outlined.Block,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            uiState.selfOdinId?.let {
                                                uriHandler.openUrl(it.buildBlockUrl(postAuthor))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // Scaffold's innerPadding already ate the navigation bar; consuming it here
                    // makes the composer's imePadding below resolve to the pure keyboard height
                    // instead of keyboard + nav bar (the gap this screen showed above the IME).
                    .consumeWindowInsets(innerPadding),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        uiState.isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }

                        post == null -> {
                            Text(
                                text = stringResource(MR.string.feed_post_detail_not_found),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                            )
                        }

                        else -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(postScrollState),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val authorOdinId = post.originalAuthor ?: post.senderOdinId
                                // Null for a public/unknown channel; a name (once `channels` loads)
                                // for a restricted one.
                                val isPublicChannel = post.channelId.isBlank() ||
                                    post.channelId == FeedProtocol.PublicChannelDriveAlias.toString()
                                val channelName = post.channelId
                                    .takeUnless { isPublicChannel }
                                    ?.let { channels[it]?.name }
                                PostCard(
                                    post = post,
                                    // Resolved via ContactService; PostAuthorHeader derives its
                                    // avatar/initials from this, falling back to the raw domain.
                                    displayName = displayNameFor(authorOdinId),
                                    channelName = channelName,
                                    isPublic = isPublicChannel,
                                    onPostClick = {},
                                    onAuthorClick = viewModel::navigateToAuthor,
                                    onMediaClick = { index ->
                                        feedMediaOverlay(
                                            post = post,
                                            index = index,
                                            title = displayNameFor(authorOdinId),
                                        )?.let { overlay = it }
                                    },
                                    onToggleReaction = viewModel::togglePostReaction,
                                    onOpenComments = {},
                                    onShowReactors = viewModel::showReactors,
                                    permission = uiState.canReact,
                                    // Unvalidated wire data — OdinId() throws on a non-domain.
                                    embeddedAuthorName = post.embeddedPost?.authorOdinId
                                        ?.takeIf { OdinId.isValid(it) }
                                        ?.let { displayNameFor(OdinId(it)) },
                                )

                                HorizontalDivider()
                                // Existing comments always render — `reactAccess` governs writing,
                                // not reading (web parity). Only the composer below is gated.
                                CommentThread(
                                    comments = uiState.comments,
                                    displayNameFor = displayNameFor,
                                    isMine = { it.isAuthoredBy(uiState.selfOdinId) },
                                    onToggleCommentReaction = viewModel::toggleCommentReaction,
                                    onReply = viewModel::startReply,
                                    onEdit = { comment, newBody ->
                                        viewModel.editComment(comment, newBody)
                                    },
                                    onDelete = viewModel::deleteComment,
                                    permission = uiState.canReact,
                                    onBlockAuthor = { author ->
                                        uiState.selfOdinId?.let {
                                            uriHandler.openUrl(it.buildBlockUrl(author))
                                        }
                                    },
                                )
                                commentDenial?.let { reason ->
                                    Text(
                                        text = stringResource(reason),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (canComment) {
                    Surface(
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding(),
                    ) {
                        CommentComposer(
                            onSend = { text, attachment ->
                                viewModel.postComment(text, attachment)
                            },
                            replyingToName = uiState.replyingTo
                                ?.let { displayNameFor(it.originalAuthor ?: it.senderOdinId) },
                            onCancelReply = viewModel::cancelReply,
                        )
                    }
                }
            }
        }

        // "Who reacted" sheet for the post — opened from the PostCard's reaction pill.
        // Non-null reactor list == sheet visible; dismiss clears it back to null. The
        // detail screen has no contact-lookup dependency, so names fall back to the
        // reactor's domain (PublicAvatar inside the sheet derives the avatar from the
        // odinId).
        uiState.reactorsSheet?.let { reactors ->
            ReactionsBottomSheet(
                reactions = reactors,
                isLoading = uiState.isReactorsLoading,
                ownerOdinId = uiState.selfOdinId?.domainName,
                summaryCounts = uiState.reactorsCounts,
                footnote = stringResource(MR.string.feed_reactors_partial)
                    .takeIf { uiState.reactorsPartial },
                onContactClick = { onAuthorClick(OdinId(it)) },
                onDismiss = viewModel::dismissReactors,
            )
        }
    }
}

/**
 * Which "you can't comment" line to show, or null when the composer should be offered (or when
 * the verdict is still resolving — no message beats a message that flips a moment later).
 * Mirrors dotyoucore-js `CantReactInfo`, whose copy these strings are taken from.
 */
private fun commentDenial(
    hasPost: Boolean,
    postAllowsComment: Boolean,
    canReact: CanReact?,
): StringResource? = when {
    !hasPost -> null
    !postAllowsComment -> MR.string.feed_post_detail_comments_disabled
    canReact == null || canReact.allowsComment -> null
    canReact !is CanReact.Denied -> MR.string.feed_post_detail_comments_disabled
    else -> when (canReact.reason) {
        DenyReason.NotAuthenticated -> MR.string.feed_comment_denied_anonymous
        DenyReason.NotAuthorized -> MR.string.feed_comment_denied_no_access
        DenyReason.DisabledOnPost -> MR.string.feed_post_detail_comments_disabled
        DenyReason.Unknown -> MR.string.feed_comment_denied_unknown
    }
}
