package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
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
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.ui.screens.feed.widget.CommentComposer
import id.homebase.core.ui.screens.feed.widget.CommentThread
import id.homebase.core.ui.screens.feed.widget.PostCard
import id.homebase.resources.MR
import id.homebase.resources.feed_post_detail_comments_disabled
import id.homebase.resources.feed_post_detail_delete
import id.homebase.resources.feed_post_detail_more_actions
import id.homebase.resources.feed_post_detail_not_found
import id.homebase.resources.feed_post_detail_report
import id.homebase.resources.feed_post_detail_title
import id.homebase.resources.menu_back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Post detail + comments. Resolves the post from the live timeline via [PostDetailViewModel],
 * renders the full [PostCard] at the top, the [CommentThread] below (gated off when the post
 * disallows comments), and a pinned [CommentComposer] at the bottom that shows the reply
 * target when one is active.
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PostDetailEvent.NavigateBack -> onBack()
                is PostDetailEvent.NavigateToAuthor -> onAuthorClick(event.odinId)
                is PostDetailEvent.ShowSnackbar -> {
                    val message = event.message ?: return@collect
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            }
        }
    }

    val post = uiState.post
    val canComment = post != null &&
        (post.reactAccess == ReactAccess.All || post.reactAccess == ReactAccess.CommentOnly)

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
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.string.feed_post_detail_delete)) },
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
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.string.feed_post_detail_report)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Flag,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    viewModel.navigateToAuthor()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
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
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val authorOdinId = post.originalAuthor ?: post.senderOdinId
                            PostCard(
                                post = post,
                                // The detail screen has no contact-lookup dependency, so
                                // fall back to the author's domain as the display name —
                                // PostAuthorHeader derives its avatar/initials from this.
                                displayName = authorOdinId?.domainName.orEmpty(),
                                channelName = null,
                                onPostClick = {},
                                onAuthorClick = viewModel::navigateToAuthor,
                                onMediaClick = {},
                                onToggleReaction = viewModel::togglePostReaction,
                                onOpenComments = {},
                                onShowReactors = {},
                            )

                            if (canComment) {
                                HorizontalDivider()
                                CommentThread(
                                    comments = uiState.comments,
                                    // The detail screen has no contact-lookup dependency, so
                                    // fall back to the author's domain as the display name.
                                    displayNameFor = { odinId -> odinId?.domainName.orEmpty() },
                                    isMine = { comment ->
                                        val self = uiState.selfOdinId
                                        self != null &&
                                            (comment.originalAuthor ?: comment.senderOdinId) == self
                                    },
                                    onToggleCommentReaction = viewModel::toggleCommentReaction,
                                    onReply = viewModel::startReply,
                                    // CommentThread's onEdit only carries the comment, not
                                    // the new body. The widget owns the inline edit field;
                                    // when it surfaces the edited text, route it to
                                    // viewModel.editComment(comment, newBody). Until that
                                    // callback exists, this is intentionally a no-op rather
                                    // than re-uploading the unchanged body.
                                    onEdit = { },
                                    onDelete = viewModel::deleteComment,
                                )
                            } else {
                                HorizontalDivider()
                                Text(
                                    text = stringResource(
                                        MR.string.feed_post_detail_comments_disabled,
                                    ),
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

            if (post != null && canComment) {
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    CommentComposer(
                        onSend = { text, attachment ->
                            viewModel.postComment(text, attachment)
                        },
                        replyingToName = uiState.replyingTo
                            ?.let { it.originalAuthor ?: it.senderOdinId }
                            ?.domainName,
                        onCancelReply = viewModel::cancelReply,
                    )
                }
            }
        }
    }
}
