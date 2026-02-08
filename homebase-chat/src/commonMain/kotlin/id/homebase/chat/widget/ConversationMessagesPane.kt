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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import id.homebase.chat.ConversationListUiAction
import id.homebase.chat.FullScreenMessageData
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.widget.video.FullScreenVideoViewer
import id.homebase.core.HomebaseConstants
import id.homebase.core.util.ScrollPosition
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ConversationMessagesPane(
    conversation: ConversationUiModel,
    messages: ImmutableList<MessageUiModel>,
    savedScrollPosition: ScrollPosition?,
    fullScreenMessageData: FullScreenMessageData?,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit,
    currentOdinId: String,
    replyToMessage: MessageUiModel?,
) {
    val coroutineScope = rememberCoroutineScope()

    // Track previous message count to detect new messages where want to scroll to bottom and not
    // restore scroll
    val previousMessageCount = remember(conversation.id) { mutableStateOf(-1) }
    // Flag to prevent saving scroll position during restoration
    var isRestoringScrollPosition by remember(conversation.id) { mutableStateOf(false) }
    // Flag to hide content until scroll position is set (prevents flash)
    var isScrollPositionReady by remember(conversation.id) { mutableStateOf(false) }
    // Group messages within day sections
    val groupedMessages =
        remember(messages) {
            val timezone = TimeZone.currentSystemDefault()
            messages
                .groupBy { message ->
                    val date = message.created.toLocalDateTime(timezone).date
                    date
                }
                .map { (date, msgs) ->
                    MessageSectionItem(
                        firstMessageTime = msgs.first().created,
                        messages = msgs,
                        date = date
                    )
                }
                .sortedBy { it.date }
                .toImmutableList()
        }
    // Calculate total items including date headers
    val totalItems =
        remember(groupedMessages) { groupedMessages.sumOf { it.messages.size + 1 } + 2 }

    // Initialize list state with saved position to prevent flash
    // Key by conversation.id so a new state is created when switching conversations
    val listState =
        remember(conversation.id) {
            val initialIndex = savedScrollPosition?.firstVisibleItemIndex ?: 0
            val initialOffset = savedScrollPosition?.firstVisibleItemScrollOffset ?: 0
            LazyListState(
                firstVisibleItemIndex = initialIndex,
                firstVisibleItemScrollOffset = initialOffset
            )
        }

    // Restore scroll position once when conversation changes and messages are loaded
    LaunchedEffect(conversation.id, messages.size) {
        if (messages.isNotEmpty()) {
            val isFirstLoad = previousMessageCount.value == -1
            val newMessagesAdded =
                previousMessageCount.value > 0 && messages.size > previousMessageCount.value

            if (isFirstLoad) {
                isRestoringScrollPosition = true
                // Show content first at initialized position
                isScrollPositionReady = true
                // Small delay to let composition happen with initialized state
                kotlinx.coroutines.delay(1)
                // On first load, scroll to saved position or bottom
                if (savedScrollPosition != null) {
                    println(
                        "Scroll to saved position: id=${conversation.id} -> ${savedScrollPosition.firstVisibleItemIndex}:${savedScrollPosition.firstVisibleItemScrollOffset}"
                    )
                    // Use scrollToItem (no animation) to prevent flash
                    listState.scrollToItem(
                        index =
                            savedScrollPosition.firstVisibleItemIndex.coerceIn(
                                0,
                                totalItems - 1
                            ),
                        scrollOffset = savedScrollPosition.firstVisibleItemScrollOffset
                    )
                } else {
                    println("Scroll to bottom: id=${conversation.id}")
                    // Use scrollToItem for instant scroll to bottom
                    listState.scrollToItem(totalItems - 1)
                }
                // Wait a bit longer than the debounce time to ensure we don't save the restored
                // position
                kotlinx.coroutines.delay(500)
                isRestoringScrollPosition = false
            } else if (newMessagesAdded) {
                // New messages added - scroll to bottom and allow saving this position
                println("New messages added, scrolling to bottom: id=${conversation.id}")
                coroutineScope.launch { listState.animateScrollToItem(totalItems - 1) }
                // Don't set isRestoringScrollPosition flag here - we want to save this scroll
                // position
            }
            previousMessageCount.value = messages.size
        } else {
            // Show empty state
            isScrollPositionReady = true
        }
    }

    // Save scroll position when it changes
    LaunchedEffect(conversation.id) {
        // Capture the current conversation ID to prevent race conditions
        val currentConversationId = conversation.id

        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(300) // Only save after 300ms of no scrolling
            .distinctUntilChanged()
            .collect { (index, offset) ->
                // Only save if we're still viewing the same conversation, messages are loaded,
                // and not restoring
                if (currentConversationId == conversation.id &&
                    messages.isNotEmpty() &&
                    !isRestoringScrollPosition
                ) {
                    println("Scroll changed: id=${conversation.id} -> $index:$offset")
                    onUiAction(
                        ConversationListUiAction.SaveScrollPosition(
                            conversationId = conversation.id,
                            firstVisibleItemIndex = index,
                            firstVisibleItemScrollOffset = offset
                        )
                    )
                }
            }
    }

    // Track the previous viewport height and scroll position before height change
    val previousViewportHeight = remember(conversation.id) { mutableStateOf(0) }
    val scrollPositionBeforeDecrease =
        remember(conversation.id) { mutableStateOf<Pair<Int, Int>?>(null) }

    // Adjust scroll position when viewport height changes
    LaunchedEffect(conversation.id) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.distinctUntilChanged()
            .collect { currentHeight ->
                val previousHeight = previousViewportHeight.value

                if (previousHeight > 0 &&
                    !isRestoringScrollPosition &&
                    listState.layoutInfo.totalItemsCount > 0
                ) {
                    when {
                        currentHeight < previousHeight -> {
                            // Height decreased (e.g., keyboard opened)
                            // Save current position before adjusting
                            scrollPositionBeforeDecrease.value =
                                listState.firstVisibleItemIndex to
                                        listState.firstVisibleItemScrollOffset

                            val heightDiff = previousHeight - currentHeight

                            // Calculate new scroll position to maintain visual position
                            val newOffset = listState.firstVisibleItemScrollOffset + heightDiff
                            listState.scrollToItem(listState.firstVisibleItemIndex, newOffset)
                        }

                        currentHeight > previousHeight &&
                                scrollPositionBeforeDecrease.value != null -> {
                            // Height increased (e.g., keyboard closed)
                            val (savedIndex, savedOffset) = scrollPositionBeforeDecrease.value!!
                            listState.scrollToItem(savedIndex, savedOffset)
                            scrollPositionBeforeDecrease.value = null
                        }
                    }
                }

                previousViewportHeight.value = currentHeight
            }
    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = fullScreenMessageData,
            transitionSpec = {
                if (targetState != null) {
                    // Entering full-screen: fade in viewer over fading out conversation
                    fadeIn(
                        tween(
                            HomebaseConstants.Animation
                                .CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION
                        )
                    ) togetherWith
                            fadeOut(
                                tween(
                                    HomebaseConstants.Animation
                                        .CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION
                                )
                            )
                } else {
                    // Exiting full-screen: instant transition back
                    fadeIn(
                        tween(
                            HomebaseConstants.Animation
                                .CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION
                        )
                    ) togetherWith
                            fadeOut(
                                tween(
                                    HomebaseConstants.Animation
                                        .CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION
                                )
                            )
                }
            }
        ) { data ->
            if (data == null) {
                ConversationContent(
                    conversation = conversation,
                    listState = listState,
                    isScrollPositionReady = isScrollPositionReady,
                    groupedMessages = groupedMessages,
                    showBackButton = showBackButton,
                    onBackClick = onBackClick,
                    onUiAction = onUiAction,
                    currentOdinId = currentOdinId,
                    replyToMessage = replyToMessage,
                    animatedVisibilityScope = this@AnimatedContent,
                    sharedTransitionScope = this@SharedTransitionLayout,
                )
            } else {
                FullScreenVideoViewer(
                    data = data,
                    onDismiss = { onUiAction(ConversationListUiAction.CloseFullScreenMedia) }
                )

//                FullScreenMediaViewer(
//                    data = data,
//                    onShare = { id, key ->
//                        onUiAction(ConversationListUiAction.ShareMedia(id, key))
//                    },
//                    onSave = { id, key ->
//                        onUiAction(ConversationListUiAction.DownloadMedia(id, key))
//                    },
//                    onDelete = { onUiAction(ConversationListUiAction.DeleteMessage(it)) },
//                    onDismiss = { onUiAction(ConversationListUiAction.CloseFullScreenMedia) },
//                    animatedVisibilityScope = this@AnimatedContent,
//                    sharedTransitionScope = this@SharedTransitionLayout,
//                )
            }
        }
    }
}
