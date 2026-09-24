package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.feed.services.isAuthoredBy
import id.homebase.core.util.buildBlockUrl
import id.homebase.core.util.getUriHandler
import id.homebase.core.ui.screens.feed.PostDetailEvent
import id.homebase.core.ui.screens.feed.PostDetailViewModel
import id.homebase.resources.MR
import id.homebase.resources.feed_comment_action_failed
import id.homebase.resources.feed_comments_title
import id.homebase.resources.feed_post_detail_comments_disabled
import id.homebase.resources.feed_post_empty_comments
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid
import id.homebase.core.util.rememberKeyboardPanelState
import id.homebase.core.util.sheetComposerInset
import id.homebase.core.util.sheetLiftsForKeyboard

// Reuses [PostDetailViewModel] keyed by [postId]; the post itself isn't re-rendered here — it's already
// visible in the feed behind the sheet.
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
    val uriHandler = getUriHandler()
    // The sheet needs its OWN host: the timeline's Scaffold snackbar renders behind it (a separate window on
    // Android), so a failed post/edit/delete would otherwise vanish.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val actionFailedMessage = stringResource(MR.string.feed_comment_action_failed)

    val displayNameFor: (OdinId?) -> String = { odinId ->
        odinId?.let { id -> uiState.displayNames[id]?.takeIf { it.isNotBlank() } }
            ?: odinId?.domainName.orEmpty()
    }

    val post = uiState.post
    val postAllowsComment = post == null ||
        post.reactAccess == ReactAccess.All ||
        post.reactAccess == ReactAccess.CommentOnly
    val canComment = postAllowsComment && uiState.canReact?.allowsComment != false

    // Read the IME *outside* the sheet: on iOS the sheet lifts its whole surface by the keyboard height and
    // then reports WindowInsets.ime as 0 to its own content.
    val composerPanel = rememberKeyboardPanelState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Zero insets: the defaults pad inside the draggable surface, so sheet height would track sheet position
        // and the anchors oscillate on fling. The composer below owns the bottom inset instead.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        // Read inside the sheet: on Android the sheet content lives in its own window, so the host's focus
        // manager doesn't drive the IME that's actually up.
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val commentsScrollState = rememberScrollState()

        // The sheet going away must take the keyboard with it. Covers every dismissal path (swipe, scrim, back, nav).
        DisposableEffect(Unit) {
            onDispose {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }

        // With the composer pinned, scrolling the thread is the only way to put the keyboard away short of sending.
        LaunchedEffect(commentsScrollState) {
            snapshotFlow { commentsScrollState.isScrollInProgress }.collect { scrolling ->
                if (scrolling) {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
            }
        }

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
                    uiState.isLoadingComments -> LoadingIndicator(modifier = Modifier.align(Alignment.Center))

                    // Reading comments is never gated — only writing is.
                    uiState.comments.isEmpty() -> CenteredHint(
                        text = stringResource(
                            if (canComment) {
                                MR.string.feed_post_empty_comments
                            } else {
                                MR.string.feed_post_detail_comments_disabled
                            },
                        ),
                    )

                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(commentsScrollState)
                            .padding(vertical = 8.dp),
                    ) {
                        CommentThread(
                            comments = uiState.comments,
                            displayNameFor = displayNameFor,
                            isMine = { it.isAuthoredBy(uiState.selfOdinId) },
                            onToggleCommentReaction = viewModel::toggleCommentReaction,
                            onReply = viewModel::startReply,
                            onEdit = { comment, newBody -> viewModel.editComment(comment, newBody) },
                            onDelete = viewModel::deleteComment,
                            permission = uiState.canReact,
                            onBlockAuthor = { author ->
                                uiState.selfOdinId?.let {
                                    uriHandler.openUrl(it.buildBlockUrl(author))
                                }
                            },
                        )
                    }
                }

                // Hosted in the list region, not over the whole sheet, so the snackbar rides above both the
                // composer and the keyboard without any inset of its own.
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            if (canComment) {
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sheetComposerInset(composerPanel),
                ) {
                    CommentComposer(
                        onSend = { text, attachment -> viewModel.postComment(text, attachment) },
                        replyingToName = uiState.replyingTo
                            ?.let { displayNameFor(it.originalAuthor ?: it.senderOdinId) },
                        onCancelReply = viewModel::cancelReply,
                        bottomPanel = composerPanel,
                        keyboardHandledByHost = sheetLiftsForKeyboard,
                    )
                }
            }
        }
    }

    // Shown from a separate scope so a lingering snackbar can't stall the collector and swallow the next event.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PostDetailEvent.NavigateToAuthor -> onAuthorClick(event.odinId)
                is PostDetailEvent.ShowSnackbar -> scope.launch {
                    snackbarHostState.showSnackbar(event.message ?: actionFailedMessage)
                }
                else -> Unit
            }
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
