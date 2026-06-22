package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.ui.screens.feed.PostDetailEvent
import id.homebase.core.ui.screens.feed.PostDetailViewModel
import id.homebase.resources.MR
import id.homebase.resources.feed_comments_title
import id.homebase.resources.feed_post_detail_comments_disabled
import id.homebase.resources.feed_post_empty_comments
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * Comments as a bottom-sheet **modal** (vs the web feed's inline expand) — the native, mobile-first
 * way to read/post comments without leaving the stream. Hosts the existing [CommentThread] (one-level
 * replies, per-comment reactions, edit/delete) over a scroll, with a pinned [CommentComposer] that
 * tracks the reply target and respects the IME.
 *
 * Reuses [PostDetailViewModel] keyed by [postId] for everything (comment stream, posting, reply,
 * reactions); the post itself isn't re-rendered here — it's already visible in the feed behind the
 * sheet. Comments are gated off when the post's [ReactAccess] disallows them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsModalSheet(
    postId: Uuid,
    onDismiss: () -> Unit,
    onAuthorClick: (OdinId) -> Unit,
    viewModel: PostDetailViewModel = koinViewModel(key = "feed-comments-$postId") {
        parametersOf(postId)
    },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val post = uiState.post
    val canComment = post == null ||
        post.reactAccess == ReactAccess.All ||
        post.reactAccess == ReactAccess.CommentOnly

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Text(
                text = stringResource(MR.string.feed_comments_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            Box(modifier = Modifier.weight(1f)) {
                when {
                    !canComment -> CenteredHint(
                        text = stringResource(MR.string.feed_post_detail_comments_disabled),
                    )

                    uiState.comments.isEmpty() -> CenteredHint(
                        text = stringResource(MR.string.feed_post_empty_comments),
                    )

                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    ) {
                        CommentThread(
                            comments = uiState.comments,
                            displayNameFor = { odinId -> odinId?.domainName.orEmpty() },
                            isMine = { comment ->
                                val self = uiState.selfOdinId
                                self != null &&
                                    (comment.originalAuthor ?: comment.senderOdinId) == self
                            },
                            onToggleCommentReaction = viewModel::toggleCommentReaction,
                            onReply = viewModel::startReply,
                            onEdit = { comment, newBody -> viewModel.editComment(comment, newBody) },
                            onDelete = viewModel::deleteComment,
                        )
                    }
                }
            }

            if (canComment) {
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    CommentComposer(
                        onSend = { text, attachment -> viewModel.postComment(text, attachment) },
                        replyingToName = uiState.replyingTo
                            ?.let { it.originalAuthor ?: it.senderOdinId }
                            ?.domainName,
                        onCancelReply = viewModel::cancelReply,
                    )
                }
            }
        }
    }

    // Author taps inside a comment row route up through the VM's event flow.
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is PostDetailEvent.NavigateToAuthor) onAuthorClick(event.odinId)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CenteredHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
    )
}
