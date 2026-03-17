package id.homebase.chat.widget

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.ConversationListUiAction.CloseFullScreenOverlay
import id.homebase.chat.conversationlist.ConversationListUiAction.DeleteMessage
import id.homebase.chat.conversationlist.ConversationListUiAction.DownloadMedia
import id.homebase.chat.conversationlist.ConversationListUiAction.SaveFile
import id.homebase.chat.conversationlist.ConversationListUiAction.SaveScrollPosition
import id.homebase.chat.conversationlist.ConversationListUiAction.SendFile
import id.homebase.chat.conversationlist.ConversationListUiAction.ShareMedia
import id.homebase.chat.conversationlist.ConversationListUiAction.UnAttachFile
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.conversationlist.MessageListContentModel
import id.homebase.chat.conversationlist.MessageListUiState
import id.homebase.chat.data.ConversationUiModel
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

inline fun <T, K, V> Iterable<T>.associateNotNull(
    transform: (T) -> Pair<K, V>?
): Map<K, V> {
    val map = mutableMapOf<K, V>()
    for (item in this) {
        val pair = transform(item)
        if (pair != null) {
            map[pair.first] = pair.second
        }
    }
    return map
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ConversationMessagesPane(
    conversation: ConversationUiModel,
    uiState: MessageListUiState,
    textFieldState: RichTextState,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    var isRestoringScrollPosition by remember { mutableStateOf(false) }
    var currentGalleryPage by remember { mutableStateOf(0) }

    val galleryLauncher = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversation.id,
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
                    conversation.id,
                    listOf(file),
                )
            )
        }
    }

    val listState = remember(conversation.id) {
        val conversationId = conversation.id

        if (uiState.scrollPosition != null) {
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

    val seen = remember(conversation.id) { mutableSetOf<Uuid>() }

    val messageIdByKey = remember(uiState.messages) {
        uiState.messages.associateNotNull { item ->
            if (item is MessageListContentModel.Message) {
                item.id to item.message.id
            } else null
        }
    }


    // Restore scroll position after groupedMessages are ready
    LaunchedEffect(conversation.id, uiState.messages) {
        isRestoringScrollPosition = true
        val totalItems = uiState.messages.size + 1 // +1 for header item
        val conversationId = conversation.id
        val currentIndex = listState.firstVisibleItemIndex

        // Only scroll if necessary
        if (uiState.scrollPosition == null && currentIndex >= totalItems) {
            // Was initialized with Int.MAX_VALUE, now scroll to actual bottom
            Logger.i("Correcting scroll to bottom: id=$conversationId (totalItems=$totalItems)")
            listState.scrollToItem(index = (totalItems - 1).coerceAtLeast(0), scrollOffset = 0)
        } else if (uiState.scrollPosition != null && currentIndex != uiState.scrollPosition.firstVisibleItemIndex) {
            // Saved position was outside bounds, re-scroll with proper coercion
            Logger.i("Correcting saved scroll position: id=$conversationId -> ${uiState.scrollPosition.firstVisibleItemIndex}:${uiState.scrollPosition.firstVisibleItemScrollOffset} (totalItems=$totalItems)")
            listState.scrollToItem(
                index = uiState.scrollPosition.firstVisibleItemIndex.coerceIn(0, totalItems - 1),
                scrollOffset = uiState.scrollPosition.firstVisibleItemScrollOffset
            )
        }
        isRestoringScrollPosition = false
    }

    // Save scroll position when it changes
    LaunchedEffect(conversation.id) {
        val currentConversationId = conversation.id
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.debounce(
            300
        ) // Only save after 300ms of no scrolling
            .distinctUntilChanged().collect { (index, offset) ->
                // Only save if we're still viewing the same conversation, messages are loaded,
                // and not restoring
                Logger.i("Scroll changed: id=${currentConversationId} -> $index:$offset")
                onUiAction(
                    SaveScrollPosition(
                        conversationId = currentConversationId,
                        firstVisibleItemIndex = index,
                        firstVisibleItemScrollOffset = offset
                    )
                )
            }
    }


    LaunchedEffect(conversation.id, messageIdByKey) {

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
                        ConversationListUiAction.MarkAsRead(newIds)
                    )
                }
            }
    }

//    // Track the previous viewport height and scroll position before height change
//    val previousViewportHeight = remember(conversation.id) { mutableStateOf(0) }
//    val scrollPositionBeforeDecrease =
//        remember(conversation.id) { mutableStateOf<Pair<Int, Int>?>(null) }
//
//    // Adjust scroll position when viewport height changes
//    LaunchedEffect(conversation.id) {
//        snapshotFlow { listState.layoutInfo.viewportSize.height }
//            .distinctUntilChanged()
//            .collect { currentHeight ->
//                val previousHeight = previousViewportHeight.value
//
//                if (previousHeight > 0 && !isRestoringScrollPosition &&
//                    listState.layoutInfo.totalItemsCount > 0
//                ) {
//                    when {
//                        currentHeight < previousHeight -> {
//                            // Height decreased (e.g., keyboard opened)
//                            // Save current position before adjusting
//                            scrollPositionBeforeDecrease.value =
//                                listState.firstVisibleItemIndex to
//                                        listState.firstVisibleItemScrollOffset
//
//                            val heightDiff = previousHeight - currentHeight
//
//                            // Calculate new scroll position to maintain visual position
//                            val newOffset = listState.firstVisibleItemScrollOffset +
//                                    heightDiff
//                            listState.scrollToItem(listState.firstVisibleItemIndex, newOffset)
//                        }
//
//                        currentHeight > previousHeight -> {
//                            // Height increased (e.g., keyboard closed)
//                            if (scrollPositionBeforeDecrease.value != null) {
//                                val (savedIndex, savedOffset) = scrollPositionBeforeDecrease.value!!
//                                listState.scrollToItem(savedIndex, savedOffset)
//                                scrollPositionBeforeDecrease.value = null
//                            } else {
//                                // If no saved position, adjust by the height difference to maintain position
//                                val heightDiff = currentHeight - previousHeight
//                                val newOffset = (listState.firstVisibleItemScrollOffset - heightDiff).coerceAtLeast(0)
//                                listState.scrollToItem(listState.firstVisibleItemIndex, newOffset)
//                            }
//                        }
//                    }
//                }
//
//                previousViewportHeight.value = currentHeight
//            }
//    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = uiState.fullScreenOverlay, transitionSpec = {
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
                    recordingData = uiState.recordingData,
                    listState = listState,
                    isScrollPositionReady = true,
                    showBackButton = showBackButton,
                    onBackClick = onBackClick,
                    onUiAction = onUiAction,
                    animatedVisibilityScope = this@AnimatedContent,
                    sharedTransitionScope = this@SharedTransitionLayout,
                )
            } else {
                when (data) {
                    is FullScreenOverlay.ViewMessageData -> {
                        FullScreenMediaViewer(
                            data = data,
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
                        )
                    }
                }
            }
        }
    }
}
