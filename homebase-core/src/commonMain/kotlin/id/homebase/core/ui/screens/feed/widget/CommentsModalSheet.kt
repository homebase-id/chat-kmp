package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
    val uriHandler = getUriHandler()
    // The sheet needs its OWN host: the timeline's Scaffold snackbar renders behind the sheet
    // (a separate window on Android), so a failed post/edit/delete would otherwise vanish.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val actionFailedMessage = stringResource(MR.string.feed_comment_action_failed)

    // Resolve comment-author names via the contact/connection map the VM streams; fall back
    // to the raw domain for unknown identities (web `AuthorName` parity).
    val displayNameFor: (OdinId?) -> String = { odinId ->
        odinId?.let { id -> uiState.displayNames[id]?.takeIf { it.isNotBlank() } }
            ?: odinId?.domainName.orEmpty()
    }

    val post = uiState.post
    // Same two gates as the detail screen: the post's own setting, then this viewer's grants.
    val postAllowsComment = post == null ||
        post.reactAccess == ReactAccess.All ||
        post.reactAccess == ReactAccess.CommentOnly
    val canComment = postAllowsComment && uiState.canReact?.allowsComment != false

    // Read the IME *outside* the sheet: on iOS the sheet lifts its whole surface by the keyboard
    // height and then reports WindowInsets.ime as 0 to its own content, so the composer can only
    // learn the keyboard is up from out here. Android keeps a live inset inside instead — hence
    // the branch below rather than a fixed modifier.
    // derivedStateOf, not a bare read: getBottom() changes every frame the IME animates, and a
    // raw read would recompose the whole sheet (comment list included) on each of those frames.
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val keyboardVisible by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Zero insets: the defaults pad inside the draggable surface, so sheet height tracks
        // sheet position and the anchors oscillate on fling (#997). The composer below owns
        // the bottom inset instead.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        // Read inside the sheet: on Android the sheet content lives in its own window, so the
        // host's focus manager / keyboard controller don't drive the IME that's actually up.
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val commentsScrollState = rememberScrollState()

        // The sheet going away must take the keyboard with it — otherwise it lingers over the
        // feed with nothing focused. Covers every dismissal path (swipe, scrim, back, nav).
        DisposableEffect(Unit) {
            onDispose {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }

        // Scrolling the thread puts the keyboard away: with the composer pinned, it is the only
        // way out short of sending.
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
                    // Reading comments is never gated — only writing is (web parity: `canReact`
                    // governs the composer, the list always renders what the post already has).
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

                // Hosted in the list region, not over the whole sheet: this Box ends where the
                // composer Surface begins, so the snackbar rides above both the composer and the
                // keyboard without any inset of its own.
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
                        // Keyboard up: pad by ime alone. On iOS that reads 0 because the sheet has
                        // already lifted itself by the keyboard height, so the composer lands flush
                        // on the keyboard; on Android it is the live keyboard inset. Either way the
                        // nav bar must NOT be added here — the keyboard covers it, and adding it
                        // parked the composer a home-indicator-height too high (measured on an
                        // iPhone 17 Pro: a 0.044-of-screen gap where chat's is 0.010).
                        // Keyboard down: the nav bar is the only inset that applies.
                        .windowInsetsPadding(
                            if (keyboardVisible) WindowInsets.ime else WindowInsets.navigationBars
                        ),
                ) {
                    CommentComposer(
                        onSend = { text, attachment -> viewModel.postComment(text, attachment) },
                        replyingToName = uiState.replyingTo
                            ?.let { displayNameFor(it.originalAuthor ?: it.senderOdinId) },
                        onCancelReply = viewModel::cancelReply,
                    )
                }
            }
        }
    }

    // Author taps inside a comment row route up through the VM's event flow; failures land on the
    // sheet's own snackbar. Shown from a separate scope so a lingering snackbar can't stall the
    // collector and swallow the next event (PostDetailScreen does the same).
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
