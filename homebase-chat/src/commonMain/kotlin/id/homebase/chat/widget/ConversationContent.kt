package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.client.profile.PublicProfileProvider
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.MessageUiModel
import kotlinx.collections.immutable.ImmutableList
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.AutoConnectRowState
import id.homebase.chat.conversationlist.ConversationListUiAction
import co.touchlab.kermit.Logger
import id.homebase.chat.dice.BattleRollSheet
import id.homebase.chat.dice.DiceRollComposerSheet
import id.homebase.chat.dice.DiceRollDescriptor
import id.homebase.chat.dice.computeBattleChainCap
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.event.EventComposerSheet
import id.homebase.chat.groodle.GroodleComposerSheet
import id.homebase.chat.poll.PollComposerSheet
import id.homebase.chat.event.EventDetailDialog
import id.homebase.chat.event.EventRsvp
import id.homebase.chat.services.renderer.PayloadRenderer
import id.homebase.chat.conversationlist.MessageClusterPosition
import id.homebase.chat.conversationlist.MessageListContentModel
import id.homebase.chat.conversationlist.MessageListUiSheet
import id.homebase.chat.conversationlist.MessageListUiState
import id.homebase.chat.conversationlist.PendingOutgoingMessage
import id.homebase.chat.conversationlist.RecipientGroupModel
import id.homebase.chat.conversationlist.RecipientModel
import id.homebase.chat.conversationlist.RecipientType
import id.homebase.chat.conversationlist.RecordingData
import id.homebase.chat.conversationlist.resolveOwnSendFollowTarget
import id.homebase.chat.createconversation.ContactItem
import id.homebase.chat.createconversation.GroupOrConversationItem
import id.homebase.chat.data.ConversationState
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.services.convo.OneOnOneConnectionStatus
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.util.boundedFirstVisibleItemIndex
import id.homebase.core.util.dismissKeyboardOnTap
import id.homebase.core.util.initials
import id.homebase.core.util.isDesktop
import id.homebase.core.util.isMobile
import id.homebase.core.util.isWeb
import id.homebase.core.util.keyboardAsState
import id.homebase.core.util.rememberImeOffsetState
import id.homebase.core.util.programmaticBackspace
import id.homebase.core.util.toMessageMarkdown
import id.homebase.core.util.rememberCameraManager
import id.homebase.core.util.rememberVideoRecorderManager
import id.homebase.core.widget.ContactName
import id.homebase.core.widget.ReactionsBottomSheet
import id.homebase.core.widget.HomebaseVerticalScrollbar
import id.homebase.core.widget.MinimalSearchTextField
import id.homebase.core.widget.StyledSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_auto_connect_connected
import id.homebase.resources.chat_group_not_connected_disclaimer
import id.homebase.resources.chat_group_rejoin_accept
import id.homebase.resources.chat_group_rejoin_decline
import id.homebase.resources.chat_group_rejoin_pending_description
import id.homebase.resources.chat_group_you_left
import id.homebase.resources.chat_group_you_were_removed
import id.homebase.resources.chat_message_block
import id.homebase.resources.chat_message_block_confirm_body
import id.homebase.resources.chat_message_block_confirm_title
import id.homebase.resources.chat_message_forward_to
import id.homebase.resources.chat_pinned_icon
import id.homebase.resources.chat_pinned_messages_empty
import id.homebase.resources.chat_pinned_messages_title
import id.homebase.resources.chat_unpin_message
import id.homebase.resources.chat_message_search_no_results
import id.homebase.resources.chat_message_search_result_count
import id.homebase.resources.chat_next_result
import id.homebase.resources.chat_load_older_messages
import id.homebase.resources.chat_no_messages
import id.homebase.resources.chat_not_connected_description
import id.homebase.resources.chat_not_connected_incoming_description
import id.homebase.resources.chat_not_connected_outgoing_description
import id.homebase.resources.chat_not_connected_review_request
import id.homebase.resources.chat_not_connected_send_request
import id.homebase.resources.chat_not_connected_view_request
import id.homebase.resources.chat_note_to_self
import id.homebase.resources.chat_options
import id.homebase.resources.chat_previous_result
import id.homebase.resources.chat_scroll_to_bottom
import id.homebase.resources.chat_search_placeholder
import id.homebase.resources.chat_send_message_button
import id.homebase.resources.connect
import id.homebase.resources.contacts
import id.homebase.resources.groups
import id.homebase.resources.menu_back
import id.homebase.resources.cd_connection_succeeded
import id.homebase.resources.recents
import id.homebase.resources.search
import id.homebase.resources.time_today
import id.homebase.resources.time_yesterday
import id.homebase.resources.you
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Upper bound on waiting for an own send to appear in the list before the
 *  follow token is consumed unscrolled (the send failed or was gated out). */
private const val OWN_SEND_FOLLOW_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ConversationContent(
    conversation: EnrichedConversationUiModel,
    uiState: MessageListUiState,
    textFieldState: RichTextState,
    searchTextState: TextFieldState,
    recordingData: RecordingData?,
    listState: LazyListState,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onUiAction: (ConversationListUiAction) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val focusRequester = remember { FocusRequester() }
    val focusRequesterSearch = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showEventComposer by remember { mutableStateOf(false) }
    var showGroodleComposer by remember { mutableStateOf(false) }
    var showDiceRollComposer by remember { mutableStateOf(false) }
    var showPollComposer by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var showConversationMenu by remember { mutableStateOf(false) }
    var showBlockConfirmDialog by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isKeyboardVisible by keyboardAsState()
    var wasKeyboardVisible by remember { mutableStateOf(isKeyboardVisible) }
    val coroutineScope = rememberCoroutineScope()
    var showScrollToBottom by remember { mutableStateOf(false) }
    var showFloatingDate by remember { mutableStateOf(false) }

    // Hoisted composer staging slot. Owned at this level so user-initiated attachments
    // (location, contact, etc.) can be appended from outside the input bar (e.g. from the
    // attachment sheet). Auto-detected attachments (link previews from typed URLs) write here
    // too, via `MessageInputBar`'s onPayloadRenderersChange callback.
    var payloadRenderers by remember { mutableStateOf<List<PayloadRenderer>>(emptyList()) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.isSearchActive) {
        if (uiState.isSearchActive) {
            focusRequesterSearch.requestFocus()
        }
    }

    LaunchedEffect(uiState.replyToMessage) {
        if (uiState.replyToMessage != null) {
            withFrameNanos {}
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(uiState.currentSearchResultIndex, uiState.searchResultMessageIds) {
        val focusedId = uiState.searchResultMessageIds.getOrNull(uiState.currentSearchResultIndex)
            ?: return@LaunchedEffect
        val idx = uiState.messages.indexOfFirst {
            it is MessageListContentModel.Message && it.message.id == focusedId
        }
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) return@snapshotFlow false
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@snapshotFlow false
            lastVisibleIndex < totalItems - 1
        }.collect { showScrollToBottom = it }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) {
                    showFloatingDate = true
                } else {
                    delay(400L)
                    showFloatingDate = false
                }
            }
    }

    // Auto-follow: when the list grows, scroll to the new last item if EITHER
    //  - the user was already at the bottom (a new incoming/sent message while
    //    reading the latest), OR
    //  - the growth is the user's OWN just-sent message — so typing while
    //    scrolled up moves you down to your message (Signal/WhatsApp behaviour).
    // Someone else's message arriving while you're scrolled up is left alone.
    //
    // When `hasNewerMessages == true` the user is paged backwards into history
    // (or the window was trimmed past MAX_WINDOW_SIZE): the bottom of the loaded
    // window is NOT the latest message, and ChatMessageStream gates incoming-stream
    // upserts (including the user's own optimistic send) on the same flag — so the
    // list can only grow via prepend here and must never auto-follow. The own-send
    // case in that state is handled separately by a reload (jumpToLatestAfterOwnSend
    // in the ViewModel), which resets hasNewerMessages and lands on the sent message.
    val hasNewerForAutoFollow = rememberUpdatedState(uiState.hasNewerMessages)
    val myOdinId = uiState.ownerSession?.odinId
    // Identity + authorship of the BOTTOM of the merged list: a trailing pending
    // outgoing placeholder (file/video send, shown before the real bubble lands)
    // or the last loaded message. The own-send arm below fires only when this
    // KEY changes to one of the user's own items — so a genuine new send at the
    // bottom follows, while an older-message prepend (which also grows the list
    // but leaves the bottom key unchanged) does NOT yank the user back down.
    val bottomItem = rememberUpdatedState(run {
        val convoId = conversation.conversation.id
        val lastMsg = (uiState.messages.lastOrNull { it is MessageListContentModel.Message }
            as? MessageListContentModel.Message)?.message
        val trailingPending = uiState.pendingOutgoing
            .filter { it.conversationId == convoId }
            .maxByOrNull { it.sentAt }
        when {
            // Pending placeholders sort to the bottom only when newer than the
            // last loaded message (mirrors the mergedItems interleave). They are
            // always the user's own outgoing content.
            trailingPending != null &&
                (lastMsg == null || trailingPending.sentAt > lastMsg.userDate) ->
                trailingPending.id to true
            lastMsg != null ->
                lastMsg.id to (myOdinId != null && lastMsg.sender == myOdinId)
            else -> null to false
        }
    })
    LaunchedEffect(listState, conversation.conversation.id) {
        var previousTotal = 0
        var wasAtBottom = false
        var previousBottomKey: Uuid? = null
        snapshotFlow {
            val info = listState.layoutInfo
            info.totalItemsCount to (info.visibleItemsInfo.lastOrNull()?.index ?: -1)
        }.collect { (total, lastVisibleIndex) ->
            val (bottomKey, bottomMine) = bottomItem.value
            val grew = total > previousTotal && previousTotal > 0
            // Own-send: the bottom item is the user's AND it just changed (a new
            // message/placeholder landed at the end), so follow it down even when
            // the user had scrolled up into history.
            val myNewBottom = bottomMine && bottomKey != null && bottomKey != previousBottomKey
            if (grew && (wasAtBottom || myNewBottom) &&
                !hasNewerForAutoFollow.value
            ) {
                listState.animateScrollToItem(total - 1)
            }
            previousBottomKey = bottomKey
            // "At the bottom" = the last item is currently visible. This mirrors
            // the scroll-to-bottom FAB's own visibility test (line ~341) and,
            // unlike canScrollForward, does NOT require scrolling the final pixels
            // into the 24.dp bottom content padding — so a fully-visible last
            // message already counts as "at the bottom" and a newly-arrived
            // message auto-follows. Recorded BEFORE the next snapshot so a
            // subsequent growth sees the pre-growth scroll state (at the moment
            // the new item lands, lastVisibleIndex still points at the old last
            // item, which is why we read it from the prior emission here).
            wasAtBottom = total > 0 && lastVisibleIndex >= total - 1
            previousTotal = total
        }
    }

    // One-time own-send follow: every send arm sets scrollToLatestRequest so the
    // user's own message always lands visible, even when scrolled up into history
    // (#995). Attachment sends put a placeholder in the list in the same update
    // that arms the token (and remount this composable, which is why the
    // layout-growth auto-follow above can't cover them — it restarts with
    // previousTotal = 0). Text/reply/location sends have NO placeholder — the
    // message reaches uiState.messages asynchronously via the optimistic-write
    // round-trip — so wait until the requested id is actually in the merged data
    // AND laid out before following; scrolling earlier targets the previous last
    // item. Skipped (but still consumed) when paged into history —
    // jumpToLatestAfterOwnSend handles that case with a ScrollToLatest reload
    // once the send completes.
    val messagesForOwnSend = rememberUpdatedState(uiState.messages)
    val pendingForOwnSend = rememberUpdatedState(uiState.pendingOutgoing)
    LaunchedEffect(uiState.scrollToLatestRequest) {
        val requestedId = uiState.scrollToLatestRequest ?: return@LaunchedEffect
        if (!uiState.hasNewerMessages) {
            // Bounded so a failed send (whose message never arrives) can't pin
            // the token armed forever; consume runs either way.
            withTimeoutOrNull(OWN_SEND_FOLLOW_TIMEOUT_MS) {
                val target = snapshotFlow {
                    resolveOwnSendFollowTarget(
                        requestedId = requestedId,
                        messages = messagesForOwnSend.value,
                        pendingOutgoing = pendingForOwnSend.value,
                        conversationId = conversation.conversation.id,
                        laidOutItemCount = listState.layoutInfo.totalItemsCount,
                    ) ?: -1
                }.first { it > 0 }
                listState.animateScrollToItem(target - 1)
            }
        }
        onUiAction(ConversationListUiAction.ConsumeScrollToLatestRequest)
    }

    // Add this state to track keyboard height
    var keyboardHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeState = rememberImeOffsetState()

    val imeVisible by remember { derivedStateOf { imeInsets.getBottom(density) > 0 } }

    // Add a LaunchedEffect to listen for keyboard changes
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            // Keyboard is shown
            keyboardHeight = with(density) { imeInsets.getBottom(density).toDp() }
            // Add any other logic you need when keyboard appears
        } else {
            // Keyboard is hidden
            // Add any logic you need when keyboard disappears
        }
    }

    // On iOS, UITextView can auto-become first responder during initial
    // composition, briefly flashing the keyboard and shifting the layout.
    // Block focus until the first frame has been laid out.  On Android/Desktop
    // the flag starts true so behaviour is unchanged.
    val needsFocusGuard = !isDesktop() && !isWeb()   // only mobile (iOS + Android)
    var inputFocusable by remember(conversation.conversation.id) {
        mutableStateOf(!needsFocusGuard)
    }

    if (needsFocusGuard) {
        LaunchedEffect(conversation.conversation.id) {
            // One frame is enough for Compose to finish layout; 150 ms covers
            // the iOS first-responder race without a noticeable typing delay.
            kotlinx.coroutines.delay(150)
            inputFocusable = true
        }
    }

    DisposableEffect(conversation.conversation.id) {
        focusManager.clearFocus()

        onDispose {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    // Build a lookup map of reply target ID -> MessageUiModel for reply image thumbnails.
    // Only includes messages that are actually referenced by a reply, to avoid O(n) map of all messages.
    val replyMessages = remember(uiState.messages) {
        val allMessages = uiState.messages.filterIsInstance<MessageListContentModel.Message>()
        val replyTargetIds = allMessages.mapNotNullTo(mutableSetOf()) {
            it.message.messageAppData.replyPreview?.replyUniqueId
        }
        if (replyTargetIds.isEmpty()) {
            persistentMapOf()
        } else {
            allMessages.filter { it.message.id in replyTargetIds }
                .associate { it.message.id to it.message }
                .toPersistentMap()
        }
    }

    // All valid dice-roll descriptors in the loaded conversation slice. Used by
    // dice-roll bubbles to render battle-leader lines and by the long-press menu
    // to gate the Battle entry on chain cap / no-rebattle. One filter per
    // message-list change, then read-only downstream.
    val allDiceDescriptors = remember(uiState.messages) {
        uiState.messages.asSequence()
            .filterIsInstance<MessageListContentModel.Message>()
            .mapNotNull { (it.message.messageContent as? MessageContent.DiceRoll)?.descriptor }
            .filter { it.isValid() }
            .toList()
            .toPersistentList()
    }

    // Maximum number of rolls a battle chain can hold in this conversation:
    // capped by the protocol's MAX_BATTLE_PARTICIPANTS, but for chats with
    // fewer members the cap is the roster itself. `participants` already
    // includes self (per ConversationService.kt:222), so it is the roster.
    // When this number is reached the bubble switches from "leads" to "won"
    // (the chain can no longer be overtaken).
    val chainCap = remember(conversation.conversation.participants.size) {
        computeBattleChainCap(conversation.conversation.participants.size)
    }

    // Save the draft whenever this thread stops — navigating away, or the app going
    // to background (where the OS may kill the process without warning). Makes the
    // periodic save the only thing the draft actually depends on: nothing here has
    // to fire for the draft to survive, it just narrows the window (#1122).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        onUiAction(ConversationListUiAction.FlushDraft)
    }

    @Suppress("DEPRECATION") BackHandler(uiState.isSearchActive || showEmojiSheet || showAttachmentSheet || isKeyboardVisible || uiState.isEditingMessageId != null) {
        if (uiState.isSearchActive) {
            onUiAction(ConversationListUiAction.SearchMessagesBackClicked)
            searchTextState.clearText()
            return@BackHandler
        }
        showEmojiSheet = false
        showAttachmentSheet = false
        keyboardController?.hide()
        // Only a back that's actually cancelling an in-progress edit should reset
        // the composer — CancelEditMessage clears it. A back that's merely
        // dismissing the keyboard must leave the typed text (and its draft) intact
        // (#1122; previously it wiped whatever you were composing).
        if (uiState.isEditingMessageId != null) {
            onUiAction(ConversationListUiAction.CancelEditMessage)
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

    // iOS: the camera sits in a DropdownMenu (a Popup window) in MessageInputBar, and FileKit's
    // camera picker can't be presented while that popup is tearing down — iOS dismisses the picker
    // along with the popup ("Take Photo opens then closes instantly"). So hoist the launch out of
    // the menu item: the item only closes the menu and flips this flag, and we present here after
    // the popup's exit transition has finished. A single recomposition isn't enough (the popup is
    // still animating out); the native video path is immune, which is why only photo broke.
    var pendingCameraLaunch by remember { mutableStateOf(false) }
    LaunchedEffect(pendingCameraLaunch) {
        if (pendingCameraLaunch) {
            // Closing the dropdown hands focus back to the input, which pops the keyboard up during
            // the wait below; clear focus + hide it so the keyboard doesn't flash before the camera.
            focusManager.clearFocus()
            keyboardController?.hide()
            delay(250) // let the DropdownMenu popup finish dismissing before FileKit presents
            cameraLauncher.launch()
            pendingCameraLaunch = false // reset AFTER launch — resetting first cancels this effect
        }
    }

    val videoRecorderLauncher = rememberVideoRecorderManager { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversation.conversation.id,
                    files = listOf(file),
                    isImage = false,
                )
            )
        }
    }
    val fileLauncher = rememberFilePickerLauncher { file ->
        file?.let {
            onUiAction(
                ConversationListUiAction.AttachPlatformFile(
                    conversationId = conversation.conversation.id,
                    files = listOf(file),
                )
            )
        }
    }
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

    ConversationContentSheets(
        uiState = uiState,
        onUiAction = onUiAction,
    )

    // Host-level event detail opened via a reply-preview tap. The per-bubble
    // EventBubble manages its own dialog for direct taps; this one covers the
    // case where the original Event is the *target* of a reply (and may be
    // outside the loaded message window — handler resolves via DB on miss).
    uiState.replyTargetEventDetail?.let { detail ->
        EventDetailDialog(
            descriptor = detail.descriptor,
            messageId = detail.messageId,
            conversationId = detail.conversationId,
            ownReactions = detail.ownReactions,
            counts = remember(detail.reactionSummary) { EventRsvp.counts(detail.reactionSummary) },
            onDismiss = { onUiAction(ConversationListUiAction.DismissEventDetailFromReply) },
            organizer = detail.organizer,
        )
    }

    uiState.messageReactions?.let { reactions ->
        ReactionsBottomSheet(
            reactions = reactions,
            isLoading = uiState.isReactionsLoading,
            ownerOdinId = uiState.ownerSession?.odinId?.domainName,
            onContactClick = { odinId ->
                onUiAction(ConversationListUiAction.ShowContactInfo(odinId))
            },
            onAddReaction = uiState.reactionDetailsMessageId?.let { messageId ->
                { emoji: String ->
                    onUiAction(
                        ConversationListUiAction.ToggleReaction(
                            conversationId = conversation.conversation.id,
                            messageId = messageId,
                            reaction = emoji,
                        )
                    )
                }
            },
            onDismiss = { onUiAction(ConversationListUiAction.HideReactionDetails) },
        )
    }

    // Sticker-tap bottom sheet (WhatsApp-style): tapping a sticker message opens this instead
    // of the fullscreen viewer. Add/Remove from the user's library + Save to device. Each row
    // dispatches then dismisses; the sheet state is cleared on dismiss.
    uiState.stickerOptionsSheet?.let { sheet ->
        StickerOptionsSheet(
            stickerImage = sheet.stickerImage,
            isAlreadySaved = sheet.isAlreadySaved,
            onAddToLibrary = {
                onUiAction(
                    ConversationListUiAction.SaveStickerFromMessage(sheet.messageId, sheet.payloadKey)
                )
                onUiAction(ConversationListUiAction.DismissStickerOptions)
            },
            onRemoveFromLibrary = {
                onUiAction(
                    ConversationListUiAction.RemoveStickerFromMessage(sheet.sourceFileId)
                )
                onUiAction(ConversationListUiAction.DismissStickerOptions)
            },
            onSaveToDevice = {
                // Reuse the existing decrypt-to-cache + SaveFileToDevice path the fullscreen
                // viewer's onSave uses — no new device-export code.
                onUiAction(
                    ConversationListUiAction.DownloadMedia(sheet.messageId, sheet.payloadKey)
                )
                onUiAction(ConversationListUiAction.DismissStickerOptions)
            },
            onDismiss = { onUiAction(ConversationListUiAction.DismissStickerOptions) },
        )
    }

    if (showBlockConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBlockConfirmDialog = false },
            title = { Text(stringResource(MR.string.chat_message_block_confirm_title)) },
            text = { Text(stringResource(MR.string.chat_message_block_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showBlockConfirmDialog = false
                    conversation.conversation.participants.firstOrNull()?.let { participant ->
                        onUiAction(ConversationListUiAction.BlockUser(participant))
                    }
                }) {
                    Text(stringResource(MR.string.chat_message_block))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirmDialog = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            }
        )
    }

    CompositionLocalProvider(
        LocalCurrentOdinId provides (uiState.ownerSession?.odinId?.domainName ?: ""),
        LocalUploadConnected provides uiState.isConnected,
    ) {
    Scaffold(
        modifier = Modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AnimatedVisibility(
                            visible = !uiState.isSearchActive,
                            enter = fadeIn(animationSpec = tween(300, delayMillis = 200)),
                            exit = fadeOut(animationSpec = tween(150))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    onUiAction(
                                        ConversationListUiAction.ShowConversationSettings(
                                            conversation.conversation
                                        )
                                    )
                                }
                            ) {
                                Spacer(modifier = Modifier.width(8.dp))
                                ConversationAvatar(
                                    modifier = Modifier.focusable(), // to avoid textfield focus
                                    avatarModel = conversation.conversation.avatarModel,
                                    options = AvatarOptions(size = 36.dp, fontSize = 14.sp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = if (conversation.conversation.isWithSelf) stringResource(
                                            MR.string.chat_note_to_self
                                        )
                                        else conversation.getDisplayName(
                                            youLabel = stringResource(
                                                MR.string.you
                                            )
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        AnimatedVisibility(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth()
                                .padding(end = 16.dp),
                            visible = uiState.isSearchActive,
                            enter = fadeIn(animationSpec = tween(200)) + expandHorizontally(
                                animationSpec = tween(300), expandFrom = Alignment.End
                            ),
                            exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally(
                                animationSpec = tween(250), shrinkTowards = Alignment.End
                            )
                        ) {
                            MinimalSearchTextField(
                                textFieldState = searchTextState,
                                modifier = Modifier.fillMaxWidth()
                                    .focusRequester(focusRequesterSearch),
                                placeHolderText = stringResource(
                                    MR.string.chat_search_placeholder
                                ),
                                showBackButton = true,
                                onBackButtonClick = {
                                    onUiAction(
                                        ConversationListUiAction.SearchMessagesBackClicked
                                    )
                                    searchTextState.clearText()
                                })
                        }
                    }
                },
                navigationIcon = {
                    if (showBackButton && !uiState.isSearchActive) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.string.menu_back)
                            )
                        }
                    }
                },
                actions = {
                    if (!uiState.isSearchActive) {
                        // One pin, either direction (#1012): my outgoing share OR someone sharing
                        // with me here. Tapping opens the live map (shows my dot + every sharer).
                        LiveShareIndicator(
                            untilMs = uiState.liveSharePinInConversationUntilMs,
                            onClick = {
                                onUiAction(ConversationListUiAction.OpenLiveLocationMap)
                            },
                        )
                        IconButton(onClick = { showConversationMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(MR.string.chat_options)
                            )
                        }
                        ConversationMenu(
                            showMenu = showConversationMenu,
                            dismissMenu = { showConversationMenu = false },
                            isGroup = conversation.conversation.isGroupConversation,
                            isArchived = conversation.conversation.conversationState == ConversationState.Archived,
                            isPinned = conversation.conversation.isPinned,
                            onConversationInfo = {
                                showConversationMenu = false
                                onUiAction(
                                    ConversationListUiAction.ShowConversationSettings(
                                        conversation.conversation
                                    )
                                )
                            },
                            onSearch = {
                                showConversationMenu = false
                                onUiAction(ConversationListUiAction.SearchMessagesClicked)
                            },
                            onDelete = {
                                showConversationMenu = false
                                onUiAction(
                                    ConversationListUiAction.DeleteConversation(
                                        conversation.conversation.id
                                    )
                                )
                            },
                            onTogglePin = {
                                showConversationMenu = false
                                onUiAction(
                                    ConversationListUiAction.TogglePinConversation(
                                        conversation.conversation.id
                                    )
                                )
                            },
                            onArchive = {
                                showConversationMenu = false
                                if (conversation.conversation.conversationState == ConversationState.Archived) {
                                    onUiAction(
                                        ConversationListUiAction.UnarchiveConversation(
                                            conversation.conversation.id
                                        )
                                    )
                                } else {
                                    onUiAction(
                                        ConversationListUiAction.ArchiveConversation(
                                            conversation.conversation.id
                                        )
                                    )
                                }
                            },
                            onClear = {
                                showConversationMenu = false
                                onUiAction(
                                    ConversationListUiAction.ClearConversation(
                                        conversation.conversation.id
                                    )
                                )
                            },
                            onIntroduceEveryone = {
                                showConversationMenu = false
                                onUiAction(
                                    ConversationListUiAction.IntroduceEveryone(
                                        conversation.conversation.id
                                    )
                                )
                            },
                            onMarkAsRead = {
                                showConversationMenu = false
                                onUiAction(
                                    ConversationListUiAction.MarkAsRead(
                                        conversation.conversation.id,
                                        messages = null,
                                    )
                                )
                            },
                            onBlock = if (!conversation.conversation.isGroupConversation && !conversation.conversation.isWithSelf) {
                                {
                                    showConversationMenu = false
                                    showBlockConfirmDialog = true
                                }
                            } else null,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .clipToBounds()
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .offset {
                        val imeHeight = imeInsets.getBottom(this)
                        val pureImeHeight = imeState.pureImeBottomPx
                        val sheetHeight = keyboardHeight.coerceAtLeast(300.dp).roundToPx()
                        val sheetOffset = when {
                            showEmojiSheet && imeHeight > 0 -> pureImeHeight + sheetHeight
                            imeHeight > 0 -> pureImeHeight
                            showEmojiSheet || showAttachmentSheet -> sheetHeight
                            else -> 0
                        }
                        IntOffset(0, -sheetOffset)
                    }
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            ) {
                PinnedMessagesBar(
                    pinnedMessages = uiState.pinnedMessages,
                    currentPinIndex = uiState.currentPinIndex,
                    onUiAction = onUiAction,
                )

                JumpTargetWaitingBar(isWaiting = uiState.awaitingJumpMessageId != null)

                if (conversation.conversation.isGroupConversation && conversation.missingConnections.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(16.dp)
                    ) {
                        Text(
                            stringResource(MR.string.chat_group_not_connected_disclaimer),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        ElevatedButton(
                            onClick = {
                                onUiAction(ConversationListUiAction.ConnectIdentities(conversation.missingConnections))
                            }) {
                            Text(stringResource(MR.string.connect))
                        }
                    }
                }

                val realMessageIds = remember(uiState.messages) {
                    uiState.messages
                        .filterIsInstance<MessageListContentModel.Message>()
                        .mapTo(HashSet()) { it.message.id }
                }
                val pendingForConvo = remember(
                    uiState.pendingOutgoing,
                    realMessageIds,
                    conversation.conversation.id
                ) {
                    uiState.pendingOutgoing.filter {
                        it.conversationId == conversation.conversation.id &&
                                it.id !in realMessageIds
                    }
                }
                // Merge pending placeholders into the messages list at the right
                // chronological position (sorted by sentAt vs. message.userDate).
                // This matters when a video is still processing — any text or
                // incoming messages with a later timestamp must render BELOW the
                // video placeholder, not above it.
                val mergedItems = remember(uiState.messages, pendingForConvo) {
                    if (pendingForConvo.isEmpty()) {
                        uiState.messages.toList<Any>()
                    } else {
                        val pending = pendingForConvo.sortedBy { it.sentAt }.toMutableList()
                        val result = mutableListOf<Any>()
                        for (item in uiState.messages) {
                            if (item is MessageListContentModel.Message) {
                                while (pending.isNotEmpty() &&
                                    pending.first().sentAt <= item.message.userDate
                                ) {
                                    result += pending.removeAt(0)
                                }
                            }
                            result += item
                        }
                        result.addAll(pending)
                        result.toList()
                    }
                }

                // Prepend compensation (#1223): when an older page lands while the viewport
                // is parked on a top marker row, key-based anchoring can't hold a message
                // steady — the "Load more" pill's key vanishes and the spinner row sits
                // ABOVE the insertion point — so the viewport teleports to the top of the
                // prepended block and the proximity trigger chain-loads from there. Pin the
                // first visible message row at its previous pixel offset instead; only
                // Message/pending rows are stable anchors (a date Section moves up when
                // older same-day messages land above it). requestScrollToItem applies at
                // the next measure, so there is no intermediate frame.
                var prevMergedItems by remember(conversation.conversation.id) {
                    mutableStateOf(mergedItems)
                }
                SideEffect {
                    if (prevMergedItems === mergedItems) return@SideEffect
                    prevMergedItems = mergedItems
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val firstKey = visible.firstOrNull()?.key
                    if (firstKey != MessageListContentModel.LoadServerHistory.id &&
                        firstKey != MessageListContentModel.LoadingOlder.id
                    ) return@SideEffect
                    val indexByKey = HashMap<String, Int>(mergedItems.size)
                    mergedItems.forEachIndexed { i, item ->
                        when (item) {
                            is MessageListContentModel.Message -> indexByKey[item.id] = i
                            is PendingOutgoingMessage -> indexByKey["pending-${item.id}"] = i
                            else -> {}
                        }
                    }
                    val anchor = visible.firstOrNull {
                        (it.key as? String)?.let(indexByKey::containsKey) == true
                    } ?: return@SideEffect
                    val newIndex = indexByKey.getValue(anchor.key as String)
                    if (newIndex > anchor.index) {
                        listState.requestScrollToItem(newIndex, -anchor.offset)
                    }
                }

                val currentMergedItems by rememberUpdatedState(mergedItems)
                // The section walk runs INSIDE the snapshotFlow block so that snapshotFlow's
                // built-in dedup compares the resolved `LocalDate?` (O(1)) instead of a
                // `Pair<Int, List<...>>` (O(n) list-equals). See CLAUDE.md "Compose & Flow
                // gotchas" — pairing index+list and adding `.distinctUntilChanged()` here
                // stalled Main for 5–8 s (build 1394 watchdog, restored by PR #472, regressed
                // by `6adcc6b1` dice-battle merge).
                val floatingDateLabel by produceState<LocalDate?>(null, listState) {
                    snapshotFlow {
                        val items = currentMergedItems
                        // boundedFirstVisibleItemIndex guards against the
                        // `Int.MAX_VALUE` "land at bottom" sentinel that
                        // ConversationMessagesPane puts into LazyListState before
                        // LazyColumn's first measure clamps it. Without the clamp
                        // this for-loop walked 2.1B iterations and froze Main for
                        // 6+ s (build 1419 watchdog, restored by PR #473, regressed
                        // by `6adcc6b1` dice-battle merge). See
                        // `boundedFirstVisibleItemIndex` docs and the CLAUDE.md
                        // "Compose & Flow gotchas" rule.
                        val startIdx = listState.boundedFirstVisibleItemIndex(items.size)
                        var date: LocalDate? = null
                        if (startIdx != null) {
                            for (i in startIdx downTo 0) {
                                val item = items.getOrNull(i)
                                if (item is MessageListContentModel.Section) {
                                    date = item.date
                                    break
                                }
                            }
                        }
                        date
                    }.collect { value = it }
                }

                // Gate `animateItem()` until the initial composition burst has settled.
                // animateItem activates `EnterExitTransitionModifierNode` + lookahead
                // measurement, which interleaves badly with `Pre-initializing scroll
                // position at bottom` mutating LazyListState during re-mount. One frame
                // is enough for the initial layout to settle; afterwards animations run
                // normally for live add/remove/move. Keyed on conversation id so switching
                // chats also gets a clean first frame.
                var animationsEnabled by remember(conversation.conversation.id) {
                    mutableStateOf(false)
                }
                LaunchedEffect(conversation.conversation.id) {
                    withFrameNanos { }
                    animationsEnabled = true
                }

                // Per-message "Read more" expanded state, owned here (above the LazyColumn)
                // so an expanded long body survives item recomposition/recycling and does
                // NOT collapse when a new message arrives. Keyed on conversation id so the
                // set clears when switching chats. See LocalExpandedMessages.
                val expandedMessages = remember(conversation.conversation.id) {
                    mutableStateMapOf<Uuid, Boolean>()
                }

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    if (!uiState.isLoadingMessages) {
                        CompositionLocalProvider(LocalExpandedMessages provides expandedMessages) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().dismissKeyboardOnTap(),
                            state = listState,
                            contentPadding = PaddingValues(
                                top = 24.dp,
                                bottom = 24.dp,
                            )
                        ) {
                            items(
                                mergedItems,
                                key = { item ->
                                    when (item) {
                                        is MessageListContentModel -> item.id
                                        is PendingOutgoingMessage -> "pending-${item.id}"
                                        else -> item.hashCode().toString()
                                    }
                                },
                            ) { item ->
                                when (item) {
                                    is MessageListContentModel.Header -> {
                                        Column(
                                            modifier = if (animationsEnabled) Modifier.animateItem() else Modifier
                                        ) {
                                            AvatarNameDisplay(
                                                modifier = Modifier.fillMaxWidth()
                                                    .padding(horizontal = 16.dp)
                                                    .padding(bottom = 16.dp),
                                                displayName = if (conversation.conversation.isWithSelf) stringResource(
                                                    MR.string.chat_note_to_self
                                                ) else conversation.getDisplayName(
                                                    youLabel = stringResource(
                                                        MR.string.you
                                                    )
                                                ),
                                                avatarModel = conversation.conversation.avatarModel,
                                                onClick = {
                                                    onUiAction(
                                                        ConversationListUiAction.ShowConversationSettings(
                                                            conversation.conversation
                                                        )
                                                    )
                                                }
                                            )

                                            if (conversation.conversation.isGroupConversation) {
                                                GroupMemberNamesCard(
                                                    modifier = Modifier.fillMaxWidth()
                                                        .padding(horizontal = 16.dp)
                                                        .padding(bottom = 16.dp),
                                                    // TODO - how to get list of nice display names
                                                    participantNames =
                                                        conversation
                                                            .participants
                                                            .filter { it.odinId != uiState.ownerSession?.odinId }
                                                            .map { it.name }
                                                            .toPersistentList(),
                                                )
                                            }
                                        }
                                    }

                                    is MessageListContentModel.Section -> {
                                        Box(
                                            modifier = if (animationsEnabled) Modifier.animateItem() else Modifier
                                        ) {
                                            MessagesSection(text = getDateSectionLabel(item.date))
                                        }
                                    }

                                    is MessageListContentModel.System -> {
                                        Box(
                                            modifier = if (animationsEnabled) Modifier.animateItem() else Modifier
                                        ) {
                                            MessagesSystemMessage(text = item.text)
                                        }
                                    }

                                    is MessageListContentModel.UnreadSeparator -> {
                                        Box(
                                            modifier = if (animationsEnabled) {
                                                Modifier.animateItem(
                                                    fadeInSpec = tween(300),
                                                    fadeOutSpec = tween(400),
                                                )
                                            } else {
                                                Modifier
                                            },
                                        ) {
                                            UnreadMessagesSeparator()
                                        }
                                    }

                                    is MessageListContentModel.Message -> {
                                        val isFocused = uiState.searchResultMessageIds.getOrNull(
                                            uiState.currentSearchResultIndex
                                        ) == item.message.id
                                        val showAuthorName = conversation.conversation.isGroupConversation &&
                                            (item.clusterPosition == MessageClusterPosition.ALONE ||
                                                item.clusterPosition == MessageClusterPosition.START)
                                        val isHighlighted = uiState.highlightedMessageId == item.message.id
                                        val highlightAlpha by animateFloatAsState(
                                            targetValue = if (isHighlighted) 0.15f else 0f,
                                            animationSpec = if (isHighlighted) tween(durationMillis = 300)
                                            else tween(durationMillis = 600),
                                        )
                                        LaunchedEffect(isHighlighted) {
                                            if (!isHighlighted) return@LaunchedEffect
                                            delay(1200L)
                                            onUiAction(ConversationListUiAction.ClearHighlightedMessage)
                                        }
                                        Box(
                                            modifier = (if (animationsEnabled) Modifier.animateItem() else Modifier)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha))
                                        ) {
                                            MessageItem(
                                                message = item.message,
                                                userDefaultReactions = uiState.userDefaultReactions,
                                                decryptedFiles = uiState.decryptedFiles,
                                                currentOdinId = uiState.ownerSession?.odinId?.domainName
                                                    ?: "",
                                                renderAuthorName = showAuthorName,
                                                isGroupConversation = conversation.conversation.isGroupConversation,
                                                clusterPosition = item.clusterPosition,
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                sharedTransitionScope = sharedTransitionScope,
                                                onUiAction = onUiAction,
                                                downloadingFiles = uiState.downloadingFiles,
                                                uploadStatus = uiState.uploadProgress[item.message.id],
                                                replyMessages = replyMessages,
                                                allDiceDescriptors = allDiceDescriptors,
                                                searchQuery = uiState.searchQuery,
                                                isCurrentSearchResult = isFocused,
                                                chainCap = chainCap,
                                                ownLiveShareUntilMs = uiState.ownLiveShareUntilMs,
                                            )
                                        }
                                    }

                                    is PendingOutgoingMessage -> {
                                        Box(
                                            modifier = if (animationsEnabled) Modifier.animateItem() else Modifier
                                        ) {
                                            PendingMessageBubble(
                                                message = item,
                                                uploadStatus = uiState.uploadProgress[item.id],
                                            )
                                        }
                                    }

                                    is MessageListContentModel.LoadingOlder,
                                    is MessageListContentModel.LoadingNewer -> {
                                        // The row is a "more exists" placeholder keeping the Header
                                        // honest; it only animates while a fetch is in flight — an
                                        // idle perpetual spinner reads as a hang.
                                        val isFetching =
                                            if (item is MessageListContentModel.LoadingOlder) uiState.isLoadingOlder
                                            else uiState.isLoadingNewer
                                        Box(
                                            modifier = (if (animationsEnabled) Modifier.animateItem() else Modifier)
                                                .fillMaxWidth()
                                                .padding(vertical = 16.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Box(modifier = Modifier.size(24.dp)) {
                                                if (isFetching) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(24.dp),
                                                        strokeWidth = 2.dp,
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    is MessageListContentModel.LoadServerHistory -> {
                                        Box(
                                            modifier = (if (animationsEnabled) Modifier.animateItem() else Modifier)
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (uiState.isLoadingOlderFromServer) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            } else {
                                                TextButton(
                                                    onClick = {
                                                        onUiAction(
                                                            ConversationListUiAction.LoadOlderMessagesFromServer(
                                                                conversation.conversation.id
                                                            )
                                                        )
                                                    }
                                                ) {
                                                    Text(stringResource(MR.string.chat_load_older_messages))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // If only one message item (the header) show no messages info
                            if (uiState.messages.size == 1 && pendingForConvo.isEmpty()) {
                                item { EmptyListItem(stringResource(MR.string.chat_no_messages)) }
                            }
                        }
                        }
                        HomebaseVerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            state = listState
                        )

                        Column(
                            modifier = Modifier.align(Alignment.TopCenter)
                                .padding(top = 8.dp),
                        ) {
                            var lastDateText by remember { mutableStateOf("") }
                            floatingDateLabel?.let { lastDateText = getDateSectionLabel(it) }

                            AnimatedVisibility(
                                visible = showFloatingDate && floatingDateLabel != null,
                                enter = slideInVertically(
                                    initialOffsetY = { -it },
                                    animationSpec = tween(100),
                                ) + fadeIn(animationSpec = tween(100)),
                                exit = slideOutVertically(
                                    targetOffsetY = { -it },
                                    animationSpec = tween(100),
                                ) + fadeOut(animationSpec = tween(100)),
                            ) {
                                Surface(
                                    shape = FloatingDateShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shadowElevation = 4.dp,
                                ) {
                                    Text(
                                        text = lastDateText,
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 6.dp,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        ScrollToBottomButton(
                            visible = showScrollToBottom,
                            unreadCount = conversation.conversation.unreadCount,
                            modifier = Modifier.align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 16.dp),
                            onClick = {
                                if (uiState.hasNewerMessages) {
                                    // Deep in history: the bottom of the loaded
                                    // window isn't the latest message, so a plain
                                    // animateScrollToItem(last) would land mid-history.
                                    // Reload the latest page instead — the VM marks
                                    // the reload pending-scroll-to-latest and the
                                    // resulting emission carries a triggerScroll
                                    // bottom anchor (resolveScrollToLatestPosition)
                                    // that ConversationMessagesPane scrolls to.
                                    // (Auto-follow can't do this: it only fires on
                                    // list growth while already at the bottom, but
                                    // this reload shrinks the window from up in
                                    // history.)
                                    onUiAction(ConversationListUiAction.ScrollToLatest(conversation.conversation.id))
                                } else {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                                    }
                                }
                            },
                        )

                    } else {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }

                AnimatedVisibility(
                    visible = uiState.isSearchActive,
                    enter = fadeIn() + expandVertically(animationSpec = tween(200)),
                    exit = fadeOut() + shrinkVertically(animationSpec = tween(150)),
                ) {
                    Surface(
                        shadowElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val resultCount = uiState.searchResultMessageIds.size
                            Text(
                                text = if (uiState.searchQuery.isEmpty()) {
                                    ""
                                } else if (resultCount == 0) {
                                    stringResource(MR.string.chat_message_search_no_results)
                                } else {
                                    stringResource(
                                        MR.string.chat_message_search_result_count,
                                        resultCount - uiState.currentSearchResultIndex,
                                        resultCount,
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { onUiAction(ConversationListUiAction.SearchMessagesNavigatePrevious) },
                                enabled = uiState.currentSearchResultIndex > 0,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = stringResource(MR.string.chat_previous_result),
                                )
                            }
                            IconButton(
                                onClick = { onUiAction(ConversationListUiAction.SearchMessagesNavigateNext) },
                                enabled = uiState.currentSearchResultIndex < uiState.searchResultMessageIds.size - 1,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(MR.string.chat_next_result),
                                )
                            }
                        }
                    }
                }

                Surface(tonalElevation = 0.dp) {
                    if (conversation.conversation.conversationState == ConversationState.Left) {
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(MR.string.chat_group_you_left),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (conversation.conversation.conversationState == ConversationState.Removed) {
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(MR.string.chat_group_you_were_removed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (conversation.conversation.conversationState == ConversationState.RejoinPending) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(MR.string.chat_group_rejoin_pending_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ElevatedButton(onClick = {
                                    onUiAction(ConversationListUiAction.AcceptRejoin(conversation.conversation.id))
                                }) {
                                    Text(stringResource(MR.string.chat_group_rejoin_accept))
                                }
                                ElevatedButton(onClick = {
                                    onUiAction(ConversationListUiAction.DeclineRejoin(conversation.conversation.id))
                                }) {
                                    Text(stringResource(MR.string.chat_group_rejoin_decline))
                                }
                            }
                        }
                    } else if (conversation.oneOnOneConnectionStatus is OneOnOneConnectionStatus.NotConnected) {
                        val status = conversation.oneOnOneConnectionStatus
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(MR.string.chat_not_connected_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ElevatedButton(onClick = {
                                onUiAction(
                                    ConversationListUiAction.OpenSendConnectionRequestDialog(
                                        status.otherOdinId
                                    )
                                )
                            }) {
                                Text(stringResource(MR.string.chat_not_connected_send_request))
                            }
                        }
                    } else if (conversation.oneOnOneConnectionStatus is OneOnOneConnectionStatus.OutgoingRequestPending) {
                        val status = conversation.oneOnOneConnectionStatus
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(MR.string.chat_not_connected_outgoing_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ElevatedButton(onClick = {
                                onUiAction(
                                    ConversationListUiAction.OpenConnectionRequestInOwnerConsole(
                                        status.otherOdinId
                                    )
                                )
                            }) {
                                Text(stringResource(MR.string.chat_not_connected_view_request))
                            }
                        }
                    } else if (conversation.oneOnOneConnectionStatus is OneOnOneConnectionStatus.IncomingRequestPending) {
                        val status = conversation.oneOnOneConnectionStatus
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(MR.string.chat_not_connected_incoming_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ElevatedButton(onClick = {
                                onUiAction(
                                    ConversationListUiAction.OpenConnectionRequestInOwnerConsole(
                                        status.otherOdinId
                                    )
                                )
                            }) {
                                Text(stringResource(MR.string.chat_not_connected_review_request))
                            }
                        }
                    } else if (!uiState.isSearchActive) {
                        var showSendButton by remember { mutableStateOf(false) }
                        var isRecordingActive by remember { mutableStateOf(false) }

                        val performSend = { text: String, attachments: List<id.homebase.chat.services.renderer.PayloadRenderer> ->
                            val hasContent = text.isNotBlank() ||
                                attachments.any { it !is id.homebase.chat.services.renderer.LinkPreviewRenderer }
                            if (hasContent) {
                                if (uiState.isEditingMessageId != null) {
                                    onUiAction(ConversationListUiAction.EditMessageSave)
                                } else {
                                    onUiAction(
                                        ConversationListUiAction.SendMessage(
                                            conversationId = conversation.conversation.id,
                                            payloadRenderers = attachments,
                                        )
                                    )
                                    payloadRenderers = emptyList()
                                }
                            }
                        }

                        val toggleAttachmentSheet = {
                            showEmojiSheet = false
                            if (showAttachmentSheet && !isKeyboardVisible) {
                                showAttachmentSheet = false
                                if (wasKeyboardVisible) {
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            } else {
                                if (isKeyboardVisible) {
                                    wasKeyboardVisible = true
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                } else {
                                    wasKeyboardVisible = false
                                }
                                showAttachmentSheet = true
                            }
                        }

                        UnifiedInputBubble(
                            replyToMessage = uiState.replyToMessage,
                            onDismissReply = {
                                onUiAction(ConversationListUiAction.CancelReplyToMessage)
                            },
                            editExistingMode = uiState.isEditingMessageId != null,
                            showSendButton = showSendButton,
                            isRecordingActive = isRecordingActive,
                            isSendingMessage = uiState.isSendingMessage,
                            onSendMessage = {
                                performSend(textFieldState.toMessageMarkdown(), payloadRenderers)
                            },
                            onCancelEdit = {
                                onUiAction(ConversationListUiAction.CancelEditMessage)
                            },
                            onAddAttachmentClick = { toggleAttachmentSheet() },
                            modifier = Modifier.focusProperties { canFocus = inputFocusable },
                        ) {
                            MessageInputBar(
                                textFieldState = textFieldState,
                                recordingData = recordingData,
                                focusRequester = focusRequester,
                                editExistingMode = uiState.isEditingMessageId != null,
                                showingEmojiSheet = showEmojiSheet,
                                isSendingMessage = uiState.isSendingMessage,
                                showActionButtons = false,
                                onSendStateChanged = { showSendButton = it },
                                onRecordingStateChanged = { isRecordingActive = it },
                                payloadRenderers = payloadRenderers,
                                onPayloadRenderersChange = { payloadRenderers = it },
                                onSendMessage = { text, attachments -> performSend(text, attachments) },
                                onEmojiClick = {
                                    showAttachmentSheet = false
                                    if (showEmojiSheet && !isKeyboardVisible) {
                                        showEmojiSheet = false
                                        if (wasKeyboardVisible) {
                                            focusRequester.requestFocus()
                                            keyboardController?.show()
                                        }
                                    } else {
                                        if (isKeyboardVisible) {
                                            wasKeyboardVisible = true
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                        } else {
                                            wasKeyboardVisible = false
                                        }
                                        showEmojiSheet = true
                                    }
                                },
                                onKeyboardClick = {
                                    showEmojiSheet = false
                                    showAttachmentSheet = false
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                },
                                onFocused = {
                                    showEmojiSheet = false
                                    showAttachmentSheet = false
                                },
                                onAddAttachmentClick = { toggleAttachmentSheet() },
                                onCameraClick = { pendingCameraLaunch = true },
                                onVideoRecordClick = { videoRecorderLauncher.launch() },
                                onRecordingStarted = {
                                    onUiAction(
                                        ConversationListUiAction.StartRecording(
                                            conversation.conversation.id
                                        )
                                    )
                                },
                                onRecordingStopped = { onUiAction(ConversationListUiAction.StopRecording) },
                                onRecordingCancelled = { onUiAction(ConversationListUiAction.CancelRecording) },
                                onRecordingHelp = { onUiAction(ConversationListUiAction.ShowRecordingHelp) },
                                onPasteImage = { imageBytes ->
                                    onUiAction(
                                        ConversationListUiAction.AttachClipboardImage(
                                            conversationId = conversation.conversation.id,
                                            imageBytes = imageBytes,
                                        )
                                    )
                                },
                                onCancelEdit = { onUiAction(ConversationListUiAction.CancelEditMessage) },
                            )
                        }
                    } // else (not Left)
                }
            }

            // Sheets live outside the offset Column so they sit flush at the screen
            // bottom without double-counting the offset.
            // Each sheet applies its modifier to its inner Column, not to the root
            // AnimatedVisibility, so we wrap in a Box to anchor them at the bottom.
            // Offset the emoji sheet upward by the IME height so that when the
            // emoji search keyboard is open, the sheet sits above the keyboard.
            Box(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .offset { IntOffset(0, -imeInsets.getBottom(this)) }) {
                ExpressionSheet(
                    visible = showEmojiSheet,
                    conversationId = conversation.conversation.id,
                    onUiAction = onUiAction,
                    onBackSpace = { textFieldState.programmaticBackspace() },
                    onEmojiSelected = { textFieldState.addTextAfterSelection(it) },
                    modifier = Modifier.fillMaxWidth()
                        .height(keyboardHeight.coerceAtLeast(300.dp)),
                )
            }

            Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                AttachmentOptionsDisplay(
                    modifier = Modifier.fillMaxWidth()
                        .height(keyboardHeight.coerceAtLeast(300.dp)),
                    visible = showAttachmentSheet && !isKeyboardVisible,
                ) {
                    // The gallery thumb strip reads the OS photo library via GalleryCache,
                    // which only exists on Android/iOS. On desktop/web the row would be
                    // empty, so we skip it entirely and let the icon row sit at the top of
                    // the sheet. The 300 dp height floor on AttachmentOptionsDisplay still
                    // applies — desktop can host a virtual keyboard, so the floor stays.
                    if (isMobile()) {
                        AttachmentGallery(
                            onImagesSelected = { images ->
                                showAttachmentSheet = false
                                onUiAction(
                                    ConversationListUiAction.AttachGalleryItem(
                                        conversationId = conversation.conversation.id,
                                        files = images
                                    )
                                )
                            },
                        )
                    }
                    AttachmentOptions(onGalleryClick = {
                        showAttachmentSheet = false
                        galleryLauncher.launch()
                    }, onFileClick = {
                        showAttachmentSheet = false
                        fileLauncher.launch()
                    }, onContactClick = {
                        showAttachmentSheet = false
                    }, onLocationClick = {
                        Logger.d(tag = "LocationShare") { "share location clicked" }
                        showAttachmentSheet = false
                        onUiAction(
                            ConversationListUiAction.OpenShareLocation(conversation.conversation.id)
                        )
                    }, onEventClick = {
                        showAttachmentSheet = false
                        showEventComposer = true
                    }, onGroodleClick = {
                        showAttachmentSheet = false
                        showGroodleComposer = true
                    }, onDicesClick = {
                        showAttachmentSheet = false
                        showDiceRollComposer = true
                    }, onPollClick = {
                        showAttachmentSheet = false
                        showPollComposer = true
                    })
                }
            } // AttachmentOptionsDisplay wrapper Box
        } // Box (clipToBounds)
    }

    if (showEventComposer) {
        EventComposerSheet(
            conversationId = conversation.conversation.id,
            onDismiss = { showEventComposer = false },
            onSent = { showEventComposer = false },
        )
    }

    if (showGroodleComposer) {
        GroodleComposerSheet(
            conversationId = conversation.conversation.id,
            onDismiss = { showGroodleComposer = false },
            onSent = { showGroodleComposer = false },
        )
    }

    if (showPollComposer) {
        PollComposerSheet(
            conversationId = conversation.conversation.id,
            onDismiss = { showPollComposer = false },
            onSent = { showPollComposer = false },
        )
    }

    if (showDiceRollComposer) {
        DiceRollComposerSheet(
            conversationId = conversation.conversation.id,
            onDismiss = { showDiceRollComposer = false },
            onSent = { showDiceRollComposer = false },
        )
    }

    val battleTarget = uiState.battleTargetMessage
    if (battleTarget != null) {
        BattleRollSheet(
            parentMessage = battleTarget,
            chainDescriptors = allDiceDescriptors,
            onDismiss = { onUiAction(ConversationListUiAction.CancelBattleDiceRoll) },
            onSent = { onUiAction(ConversationListUiAction.CancelBattleDiceRoll) },
        )
    }
    } // CompositionLocalProvider
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationContentSheets(
    uiState: MessageListUiState,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    val youLabel = stringResource(MR.string.you)
    when (val sheet = uiState.uiSheet) {
        null -> {}
        is MessageListUiSheet.ConnectIdentities -> {
            ConnectIdentitiesSheet(
                identities = sheet.identities,
                autoConnectStates = sheet.autoConnectStates,
                onDismiss = { onUiAction(ConversationListUiAction.DismissSheet) },
                onAutoConnect = { onUiAction(ConversationListUiAction.AutoConnect(it)) },
            )
        }

        is MessageListUiSheet.ForwardMessage -> {
            val sheetState = rememberModalBottomSheetState()
            val isSearching = sheet.searchTextState.text.isNotEmpty()
            val scope = rememberCoroutineScope()

            val filteredRecipients = remember(sheet.searchTextState.text) {
                if (isSearching) {
                    val query = sheet.searchTextState.text.toString()
                    sheet.recipients.mapNotNull { group ->
                        val matchingRecipients = group.recipients.filter { recipient ->
                            when (recipient) {
                                is RecipientModel.Contact -> recipient.contact.name.contains(
                                    query,
                                    ignoreCase = true
                                )
                                        || recipient.contact.odinId.domainName.contains(
                                    query,
                                    ignoreCase = true
                                )

                                is RecipientModel.Conversation -> recipient.conversation.getDisplayName(
                                    youLabel = youLabel
                                )
                                    .contains(query, ignoreCase = true)
                            }
                        }
                        if (matchingRecipients.isEmpty()) null
                        else group.copy(recipients = matchingRecipients)
                    }
                } else {
                    sheet.recipients
                }
            }

            ModalBottomSheet(
                onDismissRequest = { onUiAction(ConversationListUiAction.DismissSheet) },
                sheetState = sheetState,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(MR.string.chat_message_forward_to),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    StyledSearchTextField(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth(),
                        textFieldState = sheet.searchTextState,
                        showSearchIcon = false,
                        placeHolderText = stringResource(MR.string.search),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    RecipientsSelectorList(
                        modifier = Modifier.weight(1f),
                        recipientGroups = filteredRecipients,
                        selectedRecipients = sheet.selectedRecipients,
                        onRecipientSelected = {
                            onUiAction(ConversationListUiAction.ForwardMessageSelectRecipient(it))
                            scope.launch {
                                sheetState.expand()
                            }
                        }
                    )
                    AnimatedVisibility(
                        visible = sheet.selectedRecipients.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = sheet.selectedRecipients.joinToString { it.name },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                BlueBackgroundIconButton(
                                    onClick = {
                                        onUiAction(
                                            ConversationListUiAction.ForwardMessageSend(
                                                sheet.message,
                                                sheet.selectedRecipients
                                            )
                                        )
                                        // Dismiss optimistically — the sheet is an intent picker, not a
                                        // progress dialog. Errors surface via snackbar. Animate-hiding
                                        // before clearing state avoids the ghost-scrim freeze that
                                        // occurs when ModalBottomSheet is yanked from composition.
                                        scope.launch {
                                            sheetState.hide()
                                            onUiAction(ConversationListUiAction.DismissSheet)
                                        }
                                    },
                                    enabled = !uiState.isSendingMessage,
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(MR.string.chat_send_message_button),
                                )
                            }
                        }
                    }
                }
            }
        }

        is MessageListUiSheet.PinnedMessages -> {
            PinnedMessagesSheet(
                pinnedMessages = uiState.pinnedMessages,
                onUiAction = onUiAction,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinnedMessagesSheet(
    pinnedMessages: ImmutableList<MessageUiModel>,
    onUiAction: (ConversationListUiAction) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = { onUiAction(ConversationListUiAction.DismissSheet) },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(MR.string.chat_pinned_messages_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
            )
            if (pinnedMessages.isEmpty()) {
                Text(
                    text = stringResource(MR.string.chat_pinned_messages_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(pinnedMessages, key = { it.id }) { message ->
                        val sender = message.displayName
                            .ifBlank { message.originalAuthor?.domainName.orEmpty() }
                        val body = message.pinnedPreviewBody().truncateToCodePoints(80)
                        val previewText = if (sender.isBlank()) body else "$sender: $body"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUiAction(ConversationListUiAction.DismissSheet)
                                    onUiAction(ConversationListUiAction.ScrollToMessageId(message.id))
                                }
                                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PushPin,
                                contentDescription = stringResource(MR.string.chat_pinned_icon),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = previewText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { onUiAction(ConversationListUiAction.UnpinMessage(message.id)) },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(MR.string.chat_unpin_message),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectIdentitiesSheet(
    identities: List<OdinId>,
    autoConnectStates: Map<OdinId, AutoConnectRowState>,
    onDismiss: () -> Unit,
    onAutoConnect: (OdinId) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(scrollState)
        ) {
            identities.forEach { odinId ->
                ConnectIdentityRow(
                    odinId = odinId,
                    rowState = autoConnectStates[odinId],
                    onAutoConnect = { onAutoConnect(odinId) },
                )
            }
        }
    }
}

@Composable
private fun ConnectIdentityRow(
    odinId: OdinId,
    rowState: AutoConnectRowState?,
    onAutoConnect: () -> Unit,
) {
    val profileProvider = koinInject<PublicProfileProvider>()
    var resolvedName by remember(odinId) { mutableStateOf(odinId.domainName) }

    LaunchedEffect(odinId) {
        try {
            resolvedName = profileProvider.getPublicProfile(odinId).name
        } catch (_: Exception) {
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(
            odinId = odinId,
            profileImageData = null,
            initials = resolvedName.initials(),
            options = AvatarOptions(size = 36.dp, fontSize = 14.sp),
            sharedTransitionScope = null,
            animatedVisibilityScope = null,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            ContactName(
                odinId = odinId,
                knownName = resolvedName,
                style = MaterialTheme.typography.titleMedium,
            )
            if (rowState is AutoConnectRowState.Failed) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(rowState.res, *rowState.args.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        when (rowState) {
            AutoConnectRowState.Succeeded -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(MR.string.cd_connection_succeeded),
                        tint = SuccessGreen,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(MR.string.chat_auto_connect_connected),
                        style = MaterialTheme.typography.labelLarge,
                        color = SuccessGreen,
                    )
                }
            }
            AutoConnectRowState.Connecting -> {
                ElevatedButton(
                    onClick = {},
                    enabled = false,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            is AutoConnectRowState.Failed,
            null -> {
                ElevatedButton(onClick = onAutoConnect) {
                    Text(stringResource(MR.string.connect))
                }
            }
        }
    }
}

private val SuccessGreen = Color(0xFF2E7D32)

@Composable
fun RecipientsSelectorList(
    modifier: Modifier = Modifier,
    recipientGroups: List<RecipientGroupModel>,
    selectedRecipients: List<RecipientModel>,
    onRecipientSelected: (RecipientModel) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
    ) {
        recipientGroups.forEach { group ->
            if (group.recipientType != RecipientType.You) {
                item {
                    Text(
                        text = group.recipientType.translatedName(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
            items(group.recipients) { recipient ->
                RecipientItem(
                    recipientModel = recipient,
                    isSelected = selectedRecipients.contains(recipient),
                    onRecipientSelected = {
                        onRecipientSelected(it)
                    },
                )
            }
        }
    }
}

@Composable
private fun RecipientType.translatedName(): String {
    return when (this) {
        RecipientType.You -> ""
        RecipientType.Recents -> stringResource(MR.string.recents)
        RecipientType.Contacts -> stringResource(MR.string.contacts)
        RecipientType.Groups -> stringResource(MR.string.groups)
    }
}

@Composable
fun RecipientItem(
    recipientModel: RecipientModel,
    isSelected: Boolean,
    onRecipientSelected: (RecipientModel) -> Unit,
) {
    when (recipientModel) {
        is RecipientModel.Contact -> {
            ContactItem(
                name = recipientModel.contact.name,
                subTitle = recipientModel.contact.odinId.domainName,
                selectionMode = true,
                isSelected = isSelected,
                odinId = recipientModel.contact.odinId,
                avatarInitials = recipientModel.contact.avatarInitials,
                onContactClick = {
                    onRecipientSelected(recipientModel)
                },
            )
        }

        is RecipientModel.Conversation -> {
            GroupOrConversationItem(
                avatarModel = recipientModel.conversation.conversation.avatarModel,
                name = recipientModel.conversation.getDisplayName(youLabel = stringResource(MR.string.you)),
                selectionMode = true,
                isSelected = isSelected,
                onContactClick = {
                    onRecipientSelected(recipientModel)
                },
            )
        }
    }

}

private val FloatingDateShape = RoundedCornerShape(12.dp)

@Composable
private fun getDateSectionLabel(messageDate: LocalDate): String {
    val timezone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(timezone).date
    val yesterday = today.minus(1, DateTimeUnit.DAY)

    return when (messageDate) {
        today -> stringResource(MR.string.time_today)
        yesterday -> stringResource(MR.string.time_yesterday)
        else -> {
            val format = LocalDate.Format {
                monthName(MonthNames.ENGLISH_ABBREVIATED)
                char(' ')
                day()
            }
            messageDate.format(format)
        }
    }
}

@Composable
private fun ScrollToBottomButton(
    visible: Boolean,
    unreadCount: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.animation.AnimatedVisibility(
                visible = unreadCount > 0,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                val badgeScale = remember { Animatable(1f) }
                var previousCount by remember { mutableIntStateOf(unreadCount) }
                LaunchedEffect(unreadCount) {
                    if (unreadCount > previousCount) {
                        badgeScale.animateTo(
                            targetValue = 1f,
                            animationSpec = keyframes {
                                durationMillis = 300
                                1.25f at 100
                                0.95f at 200
                                1f at 300
                            },
                        )
                    }
                    previousCount = unreadCount
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.graphicsLayer {
                            scaleX = badgeScale.value
                            scaleY = badgeScale.value
                        },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            text = if (unreadCount > 999) "999+" else unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
            SmallFloatingActionButton(
                onClick = onClick,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(MR.string.chat_scroll_to_bottom),
                )
            }
        }
    }
}
