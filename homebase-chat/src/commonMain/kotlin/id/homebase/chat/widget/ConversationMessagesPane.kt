package id.homebase.chat.widget

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import associateNotNull
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.ConversationListUiAction.CloseFullScreenOverlay
import id.homebase.chat.conversationlist.ConversationListUiAction.DeleteMessage
import id.homebase.chat.conversationlist.ConversationListUiAction.DownloadMedia
import id.homebase.chat.conversationlist.ConversationListUiAction.DownloadVideoMedia
import id.homebase.chat.conversationlist.ConversationListUiAction.SaveFile
import id.homebase.chat.conversationlist.ConversationListUiAction.SaveScrollPosition
import id.homebase.chat.conversationlist.ConversationListUiAction.SendFile
import id.homebase.chat.conversationlist.ConversationListUiAction.ShareMedia
import id.homebase.chat.conversationlist.ConversationListUiAction.UnAttachFile
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.conversationlist.MessageListContentModel
import id.homebase.chat.conversationlist.MessageListUiState
import id.homebase.chat.conversationlist.resolveAnchorMessageId
import id.homebase.chat.services.PaginatedConversationState
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.core.HomebaseConstants
import id.homebase.core.util.boundedFirstVisibleItemIndex
import id.homebase.core.util.rememberCameraManager
import id.homebase.resources.MR
import id.homebase.resources.cd_send_to
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ConversationMessagesPane(
    conversation: EnrichedConversationUiModel,
    uiState: MessageListUiState,
    textFieldState: RichTextState,
    searchTextState: TextFieldState,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    var currentGalleryPage by remember { mutableStateOf(0) }

    val galleryLauncher = rememberFilePickerLauncher(
        type = FileKitType.ImageAndVideo,
        mode = FileKitMode.Multiple(),
    ) { files ->
        files?.takeIf { it.isNotEmpty() }?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversation.conversation.id,
                    files = it,
                    isImage = true,
                )
            )
        }
    }
    val fileLauncher = rememberFilePickerLauncher { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversation.conversation.id,
                    listOf(file),
                )
            )
        }
    }
    val cameraLauncher = rememberCameraManager { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversation.conversation.id,
                    files = listOf(file),
                    isImage = true,
                )
            )
        }
    }

    val listState = remember(conversation.conversation.id,uiState.isLoadingMessages) {
        val conversationId = conversation.conversation.id
        if (uiState.isLoadingMessages) {
            Logger.i("Pre-initializing empty scroll position: id=$conversationId")
            LazyListState()
        } else if (uiState.scrollPosition != null) {
            Logger.i("Pre-initializing scroll position: id=$conversationId -> ${uiState.scrollPosition.firstVisibleItemIndex}:${uiState.scrollPosition.firstVisibleItemScrollOffset}")
            LazyListState(
                firstVisibleItemIndex = uiState.scrollPosition.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = uiState.scrollPosition.firstVisibleItemScrollOffset
            )
        } else {
            Logger.i("Pre-initializing scroll position at bottom: id=$conversationId")
            // Start at a high index - will be corrected when messages load
            LazyListState(firstVisibleItemIndex = Int.MAX_VALUE, firstVisibleItemScrollOffset = 0)
        }
    }

    // The LaunchedEffect below is keyed only on `listState`, so its captured
    // `uiState.messages` would freeze at composition time. `rememberUpdatedState`
    // gives the lambda a State handle whose `.value` reads the latest messages
    // list at each emission — so the index → anchor resolution always uses the
    // currently-rendered window, not a stale one.
    val latestMessages = rememberUpdatedState(uiState.messages)

    // Save scroll position when it changes
    LaunchedEffect(listState) {
        val conversationId = conversation.conversation.id
        // boundedFirstVisibleItemIndex against `layoutInfo.totalItemsCount` (what
        // LazyColumn actually rendered) drops Int.MAX_VALUE sentinel emissions
        // that snapshotFlow can see before LazyColumn's first measure clamps the
        // LazyListState. Without this guard, a Main-busy window > 300 ms could
        // persist MAX_VALUE to user prefs; the next session would then
        // reconstruct LazyListState from prefs with MAX_VALUE again, re-arming
        // the same race we just closed in ConversationContent.kt.
        snapshotFlow {
            listState.boundedFirstVisibleItemIndex(listState.layoutInfo.totalItemsCount) to
                listState.firstVisibleItemScrollOffset
        }.debounce(300) // Only save after 300ms of no scrolling
            .collect { (index, offset) ->
                // Only save if we're still viewing the same conversation, messages are loaded,
                // and not restoring
                if (index != null && !uiState.isLoadingMessages) {
                    val anchorId = resolveAnchorMessageId(latestMessages.value, index)
                    if (anchorId != null) {
                        Logger.i("Scroll changed: id=${conversationId} -> anchor=$anchorId offset=$offset (idx=$index)")
                        onUiAction(
                            SaveScrollPosition(
                                conversationId = conversationId,
                                anchorMessageId = anchorId,
                                firstVisibleItemScrollOffset = offset,
                            )
                        )
                    }
                }
            }
    }

    LaunchedEffect(uiState.scrollPosition) {
        if (uiState.scrollPosition?.triggerScroll == true) {
            listState.scrollToItem(
                uiState.scrollPosition.firstVisibleItemIndex,
                uiState.scrollPosition.firstVisibleItemScrollOffset
            )
            onUiAction(ConversationListUiAction.ClearScrollTrigger)
        }
    }

    // Proximity-trigger: when the visible window approaches either end and the
    // window has more pages on that side, dispatch a load. The flag reads use
    // rememberUpdatedState so the collect block always sees the latest values
    // without restarting the snapshotFlow on every uiState mutation. The
    // snapshotFlow body intentionally returns a tiny `Pair<Boolean, Boolean>`
    // — snapshotFlow's structural dedup makes that O(1), unlike a list-shaped
    // emission which would re-cost a full equals on every scroll tick.
    val hasOlderState = rememberUpdatedState(uiState.hasOlderMessages)
    val hasNewerState = rememberUpdatedState(uiState.hasNewerMessages)
    val isLoadingOlderState = rememberUpdatedState(uiState.isLoadingOlder)
    val isLoadingNewerState = rememberUpdatedState(uiState.isLoadingNewer)
    LaunchedEffect(listState, conversation.conversation.id) {
        val conversationId = conversation.conversation.id
        val threshold = PaginatedConversationState.LOAD_MORE_THRESHOLD
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@snapshotFlow false to false
            val firstIdx = listState.boundedFirstVisibleItemIndex(total)
                ?: return@snapshotFlow false to false
            val lastIdx = info.visibleItemsInfo.lastOrNull()?.index ?: firstIdx
            val nearTop = firstIdx < threshold
            val nearBottom = lastIdx > total - 1 - threshold
            nearTop to nearBottom
        }.collect { (nearTop, nearBottom) ->
            if (nearTop && hasOlderState.value && !isLoadingOlderState.value) {
                onUiAction(ConversationListUiAction.LoadOlderMessages(conversationId))
            }
            if (nearBottom && hasNewerState.value && !isLoadingNewerState.value) {
                onUiAction(ConversationListUiAction.LoadNewerMessages(conversationId))
            }
        }
    }

    val seen = remember(conversation.conversation.id) { mutableSetOf<Uuid>() }
    // Map row-key → the full MessageUiModel that's already loaded in the
    // window. Used by the visibility watcher below to pass real models into
    // MarkAsRead — the handler reads localReadTimestamp / isDeleted /
    // isPendingSend / fileId / userDate / isAuthoredBy(domain) straight off
    // the model, so we don't need to round-trip the DB to look them back up.
    val messageByKey = remember(uiState.messages) {
        uiState.messages.associateNotNull { item ->
            if (item is MessageListContentModel.Message) {
                item.id to item.message
            } else null
        }
    }

    LaunchedEffect(conversation.conversation.id, messageByKey) {

        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .debounce(500)
            .map { visibleItems ->
                visibleItems
                    .mapNotNull { it.key as? String }
                    .mapNotNull { key -> messageByKey[key] }
            }
            .flowOn(Dispatchers.Default) // MOVE WORK OFF MAIN THREAD
            .collect { visibleMessages ->

                val newMessages = visibleMessages.filterNot { it.id in seen }

                if (newMessages.isNotEmpty()) {
                    seen.addAll(newMessages.map { it.id })

                    onUiAction(
                        ConversationListUiAction.MarkAsRead(
                            conversation.conversation.id,
                            newMessages,
                        )
                    )
                }
            }
    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = uiState.fullScreenOverlay,
            // Key on the overlay's type only. Mutating fields on the same overlay
            // (e.g. trim handle drag → new AttachmentData) would otherwise be seen
            // as a new screen and trigger a fade-out + remount of the inline
            // editor, dropping its `remember` state and re-extracting thumbnails.
            contentKey = { overlay ->
                when (overlay) {
                    null -> "none"
                    is FullScreenOverlay.ViewMessageData -> "view"
                    is FullScreenOverlay.VideoPlayerData -> "videoPlayer"
                    is FullScreenOverlay.AttachmentData -> "attachment"
                    is FullScreenOverlay.PdfViewerData -> "pdf"
                }
            },
            transitionSpec = {
                if (targetState != null) {
                    // Entering full-screen: fade in viewer over fading out conversation
                    fadeIn(
                        tween(
                            HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION
                        )
                    ) togetherWith fadeOut(
                        tween(
                            HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION
                        )
                    )
                } else {
                    // Exiting full-screen: instant transition back
                    fadeIn(
                        tween(
                            HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION
                        )
                    ) togetherWith fadeOut(
                        tween(
                            HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION
                        )
                    )
                }
            }) { data ->
            if (data == null) {
                ConversationContent(
                    conversation = conversation,
                    uiState = uiState,
                    textFieldState = textFieldState,
                    searchTextState = searchTextState,
                    recordingData = uiState.recordingData,
                    listState = listState,
                    showBackButton = showBackButton,
                    onBackClick = onBackClick,
                    onUiAction = onUiAction,
                    animatedVisibilityScope = this@AnimatedContent,
                    sharedTransitionScope = this@SharedTransitionLayout
                )
            } else {
                when (data) {
                    is FullScreenOverlay.ViewMessageData -> {
                        FullScreenMediaViewer(
                            data = data,
                            isDownloading = "${data.messageId}_${data.selectedPayloadKey}" in uiState.downloadingFiles,
                            onShare = { id, key -> onUiAction(ShareMedia(id, key)) },
                            onSave = { message, key ->
                                onUiAction(DownloadMedia(message, key))
                            },
                            onSaveSticker = { message, key ->
                                onUiAction(
                                    ConversationListUiAction.SaveStickerFromMessage(message, key)
                                )
                            },
                            onDelete = { onUiAction(DeleteMessage(it)) },
                            onDismiss = { onUiAction(CloseFullScreenOverlay) },
                            animatedVisibilityScope = this@AnimatedContent,
                            sharedTransitionScope = this@SharedTransitionLayout,
                        )
                    }

                    is FullScreenOverlay.VideoPlayerData -> {
                        FullScreenVideoPlayer(
                            data = data,
                            isDownloading = "${data.fileId}_${data.payloadKey}" in uiState.downloadingFiles,
                            onDismiss = { onUiAction(CloseFullScreenOverlay) },
                            onSave = { onUiAction(DownloadVideoMedia(data.fileId, data.payloadKey, data.keyHeader, data.payload)) },
                            uploadStatus = data.uploadMessageId?.let { uiState.uploadProgress[it] },
                        )
                    }

                    is FullScreenOverlay.PdfViewerData -> {
                        ChatPdfViewer(
                            title = data.title,
                            filePath = uiState.decryptedFiles[
                                DecryptedFileKey(data.fileId, data.payloadKey)
                            ],
                            isDownloading = "${data.messageId}_${data.payloadKey}" in uiState.downloadingFiles,
                            onDownload = { onUiAction(DownloadMedia(data.messageId, data.payloadKey)) },
                            onDismiss = { onUiAction(CloseFullScreenOverlay) },
                        )
                    }

                    is FullScreenOverlay.AttachmentData -> {
                        MediaAttachmentEditor(
                            attachments = data.attachments,
                            currentPage = currentGalleryPage,
                            onPageChanged = { currentGalleryPage = it },
                            onSaveFile = { onUiAction(SaveFile(it)) },
                            onAddFile = { fileLauncher.launch() },
                            onAddImage = { galleryLauncher.launch() },
                            onCameraClick = { cameraLauncher.launch() },
                            onRemoveFile = { attachmentId ->
                                onUiAction(UnAttachFile(data.conversationId, attachmentId))
                            },
                            onDismiss = { onUiAction(CloseFullScreenOverlay) },
                            onCropImage = { attachmentId ->
                                onUiAction(
                                    id.homebase.chat.conversationlist.ConversationListUiAction.RequestCropAttachment(
                                        data.conversationId, attachmentId,
                                    )
                                )
                            },
                            onDrawImage = { attachmentId ->
                                onUiAction(
                                    id.homebase.chat.conversationlist.ConversationListUiAction.RequestDrawAttachment(
                                        data.conversationId, attachmentId,
                                    )
                                )
                            },
                            onTrimChange = { attachmentId, startMs, endMs ->
                                onUiAction(
                                    id.homebase.chat.conversationlist.ConversationListUiAction.ApplyTrimResult(
                                        data.conversationId, attachmentId, startMs, endMs,
                                    )
                                )
                            },
                            pagerTopEndSlot = {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(16.dp)
                                        .fillMaxWidth(0.6f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = stringResource(MR.string.cd_send_to, data.conversationTitle),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = data.conversationTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            bottomBar = {
                                MessageTextFieldForAttachment(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp).imePadding(),
                                    state = textFieldState,
                                    onSmileyClick = {},
                                    onSendMessage = {
                                        onUiAction(SendFile(data.conversationId, textFieldState.toMarkdown().trimEnd(), data.attachments))
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
