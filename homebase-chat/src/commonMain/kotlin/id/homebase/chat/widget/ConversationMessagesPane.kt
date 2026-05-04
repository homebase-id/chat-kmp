package id.homebase.chat.widget

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.conversationlist.MessageListContentModel
import id.homebase.chat.conversationlist.MessageListUiState
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.core.HomebaseConstants
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

    val galleryLauncher = rememberFilePickerLauncher(type = FileKitType.ImageAndVideo) { file ->
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

    // Save scroll position when it changes
    LaunchedEffect(listState) {
        val conversationId = conversation.conversation.id
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.debounce(
            300
        ) // Only save after 300ms of no scrolling
            .distinctUntilChanged().collect { (index, offset) ->
                // Only save if we're still viewing the same conversation, messages are loaded,
                // and not restoring
                if (!uiState.isLoadingMessages) {
                    Logger.i("Scroll changed: id=${conversationId} -> $index:$offset")
                    onUiAction(
                        SaveScrollPosition(
                            conversationId = conversationId,
                            firstVisibleItemIndex = index,
                            firstVisibleItemScrollOffset = offset
                        )
                    )
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

    val seen = remember(conversation.conversation.id) { mutableSetOf<Uuid>() }
    val messageIdByKey = remember(uiState.messages) {
        uiState.messages.associateNotNull { item ->
            if (item is MessageListContentModel.Message) {
                item.id to item.message.id
            } else null
        }
    }

    LaunchedEffect(conversation.conversation.id, messageIdByKey) {

        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .debounce(500)
            .map { visibleItems ->
                visibleItems
                    .mapNotNull { it.key as? String }
                    .mapNotNull { key -> messageIdByKey[key] }
            }
            .flowOn(Dispatchers.Default) // MOVE WORK OFF MAIN THREAD
            .collect { visibleIds ->

                val newIds = visibleIds.filterNot { it in seen }

                if (newIds.isNotEmpty()) {
                    seen.addAll(newIds)

                    onUiAction(
                        ConversationListUiAction.MarkAsRead(conversation.conversation.id, newIds)
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

                    is FullScreenOverlay.AttachmentData -> {
                        FullScreenAttachmentEditor(
                            data = data,
                            textFieldState = textFieldState,
                            currentPage = currentGalleryPage,
                            onPageChanged = { currentGalleryPage = it },
                            onSaveFile = { onUiAction(SaveFile(it)) },
                            onAddFile = { fileLauncher.launch() },
                            onAddImage = { galleryLauncher.launch() },
                            onRemoveFile = { conversationId, attachmentId ->
                                onUiAction(UnAttachFile(conversationId, attachmentId))
                            },
                            onSendMessage = { conversationId, message, files ->
                                onUiAction(SendFile(conversationId, message, files))
                            },
                            onDismiss = { onUiAction(CloseFullScreenOverlay) },
                            onCropImage = { conversationId, attachmentId ->
                                onUiAction(
                                    id.homebase.chat.conversationlist.ConversationListUiAction.RequestCropAttachment(
                                        conversationId,
                                        attachmentId,
                                    )
                                )
                            },
                            onDrawImage = { conversationId, attachmentId ->
                                onUiAction(
                                    id.homebase.chat.conversationlist.ConversationListUiAction.RequestDrawAttachment(
                                        conversationId,
                                        attachmentId,
                                    )
                                )
                            },
                            onTrimChange = { conversationId, attachmentId, startMs, endMs ->
                                onUiAction(
                                    id.homebase.chat.conversationlist.ConversationListUiAction.ApplyTrimResult(
                                        conversationId,
                                        attachmentId,
                                        startMs,
                                        endMs,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
