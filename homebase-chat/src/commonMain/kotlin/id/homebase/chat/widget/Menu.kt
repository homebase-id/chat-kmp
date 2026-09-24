package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Transition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.content.ActionPolicy
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.MessageForward
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.util.isMobile
import id.homebase.core.widget.ListItemActionNormalIcon
import id.homebase.core.widget.ReactionMenu
import id.homebase.resources.MR
import id.homebase.resources.chat_archive
import id.homebase.resources.chat_clear
import id.homebase.resources.chat_delete
import id.homebase.resources.chat_filter_by_unread_button
import id.homebase.resources.chat_filter_by_unread_clear_button
import id.homebase.resources.chat_group_introduce_everyone
import id.homebase.resources.chat_group_settings
import id.homebase.resources.chat_mark_all_as_read
import id.homebase.resources.chat_message_block
import id.homebase.resources.chat_message_copy
import id.homebase.resources.chat_message_edit
import id.homebase.resources.chat_message_forward
import id.homebase.resources.chat_message_info
import id.homebase.resources.chat_dice_battle_action
import id.homebase.resources.chat_message_reply
import id.homebase.resources.chat_message_report
import id.homebase.resources.chat_pin
import id.homebase.resources.chat_pin_message
import id.homebase.resources.chat_unpin_message
import id.homebase.resources.chat_save_sticker
import id.homebase.resources.chat_settings
import id.homebase.resources.chat_unarchive
import id.homebase.resources.chat_unpin
import id.homebase.resources.conversation_media_go_to_message
import id.homebase.resources.delete
import id.homebase.resources.save
import id.homebase.resources.search
import id.homebase.resources.settings
import id.homebase.resources.share
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlin.math.pow

@Composable
fun ConversationMenu(
    showMenu: Boolean,
    dismissMenu: () -> Unit,
    isGroup: Boolean,
    isArchived: Boolean,
    isPinned: Boolean,
    onConversationInfo: () -> Unit,
    onSearch: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onClear: () -> Unit,
    onIntroduceEveryone: () -> Unit,
    onMarkAsRead: (() -> Unit)? = null,
    onBlock: (() -> Unit)? = null,
) {
    DropdownMenu(
        shape = RoundedCornerShape(Dimens.Message.cornerRadius),
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        DropdownMenuItem(
            onClick = onConversationInfo,
            text = {
                Text(
                    text = if (isGroup) stringResource(MR.string.chat_group_settings) else stringResource(
                        MR.string.chat_settings
                    )
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null
                )
            }
        )
        HorizontalDivider()

        DropdownMenuItem(
            onClick = onSearch,
            text = { Text(text = stringResource(MR.string.search)) },
            leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) })

        if (onMarkAsRead != null) {
            DropdownMenuItem(
                onClick = onMarkAsRead,
                text = { Text(text = stringResource(MR.string.chat_mark_all_as_read)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.MarkChatRead, contentDescription = null)
                },
            )
        }

        HorizontalDivider()

        DropdownMenuItem(
            onClick = onDelete,
            text = { Text(text = stringResource(MR.string.chat_delete)) },
            leadingIcon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) })
        DropdownMenuItem(
            onClick = onTogglePin,
            text = { Text(text = stringResource(if (isPinned) MR.string.chat_unpin else MR.string.chat_pin)) },
            leadingIcon = { Icon(imageVector = Icons.Filled.PushPin, contentDescription = null) })
        DropdownMenuItem(
            onClick = onArchive,
            text = { Text(text = stringResource(if (isArchived) MR.string.chat_unarchive else MR.string.chat_archive)) },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Archive, contentDescription = null)
            })
        DropdownMenuItem(
            onClick = onClear,
            text = { Text(text = stringResource(MR.string.chat_clear)) },
            leadingIcon = { Icon(imageVector = Icons.Filled.Clear, contentDescription = null) })


        if (isGroup) {
            HorizontalDivider()

            DropdownMenuItem(
                onClick = onIntroduceEveryone,
                text = {
                    Text(
                        text = stringResource(MR.string.chat_group_introduce_everyone)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Handshake,
                        contentDescription = null
                    )
                }
            )
        }

        if (onBlock != null) {
            HorizontalDivider()
            DropdownMenuItem(
                onClick = onBlock,
                text = { Text(text = stringResource(MR.string.chat_message_block)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = null
                    )
                }
            )
        }
    }
}

@Composable
fun ReceivedMessagePopup(
    transition: Transition<MessagePopupMode>,
    message: MessageUiModel,
    userDefaultReactions: ImmutableList<String>,
    dismissMenu: () -> Unit,
    onSelectEmoji: (String) -> Unit,
    onShowAllEmojis: () -> Unit,
    onMessageInfo: () -> Unit,
    onReply: (() -> Unit)?,
    onBattle: (() -> Unit)? = null,
    onForward: (() -> Unit)?,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onBlock: () -> Unit,
    onReport: () -> Unit,
) {
    val policy = message.messageContent?.actions ?: ActionPolicy.Standard
    val actionMenu = remember(policy, onBattle, message.isPinned) {
        movableContentOf<Unit> {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .wrapContentWidth(),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 4.dp,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.width(IntrinsicSize.Max)
                ) {
                    ListItemActionNormalIcon(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onMessageInfo,
                        text = stringResource(MR.string.chat_message_info),
                        imageVector = Icons.Default.Info,
                    )
                    if (onReply != null) {
                        ListItemActionNormalIcon(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onReply,
                            text = stringResource(MR.string.chat_message_reply),
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                        )
                    }
                    if (onBattle != null) {
                        ListItemActionNormalIcon(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onBattle,
                            text = stringResource(MR.string.chat_dice_battle_action),
                            imageVector = Icons.Filled.Casino,
                        )
                    }
                    if (onForward != null) {
                        ListItemActionNormalIcon(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onForward,
                            text = stringResource(MR.string.chat_message_forward),
                            imageVector = HomebaseIcons.MessageForward,
                        )
                    }
                    ListItemActionNormalIcon(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCopy,
                        text = stringResource(MR.string.chat_message_copy),
                        imageVector = Icons.Default.ContentCopy,
                    )
                    ListItemActionNormalIcon(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onTogglePin,
                        text = stringResource(
                            if (message.isPinned) MR.string.chat_unpin_message
                            else MR.string.chat_pin_message
                        ),
                        imageVector = Icons.Filled.PushPin,
                    )
                    ListItemActionNormalIcon(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDelete,
                        text = stringResource(MR.string.delete),
                        imageVector = Icons.Filled.Delete,
                    )
                    HorizontalDivider()
                    ListItemActionNormalIcon(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onBlock,
                        text = stringResource(MR.string.chat_message_block),
                        imageVector = Icons.Filled.Block,
                    )
                    ListItemActionNormalIcon(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onReport,
                        text = stringResource(MR.string.chat_message_report),
                        imageVector = Icons.Filled.Flag,
                    )
                }
            }
        }
    }

    val reactionBar: @Composable (Modifier, (Int) -> Modifier) -> Unit = { background, emoji ->
        ReactionMenu(
            modifier = Modifier.padding(horizontal = 16.dp),
            userDefaultReactions = userDefaultReactions,
            ownReactions = message.ownReactions,
            onSelect = onSelectEmoji,
            onShowAllEmojis = onShowAllEmojis,
            backgroundModifier = background,
            emojiModifier = emoji,
        )
    }
    when (transition.shownMode) {
        MessagePopupMode.Reaction -> {
            // This unanchored Popup positions against its parent — the hover-icons Row,
            // which only has icons (and therefore a size) on desktop. Mobile renders the
            // bar from the bubble itself via BubbleReactionPopup.
            if (policy.allowInlineReactions && !isMobile()) {
                Popup(
                    onDismissRequest = dismissMenu
                ) {
                    transition.PopupContent { reactionBar(Modifier) { Modifier } }
                }
            }
        }
        MessagePopupMode.Menu -> {
            Popup(
                onDismissRequest = dismissMenu
            ) {
                transition.PopupContent { actionMenu(Unit) }
            }
        }
        MessagePopupMode.All -> {
            PopupWithScrim(
                transition = transition,
                onDismissRequest = dismissMenu
            ) {
                MessageLongPressLayout(
                    alignEnd = false,
                    reactionMenu = reactionBar.takeIf { policy.allowInlineReactions },
                    bubble = {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            ReceivedMessageBubbleDisplayOnly(message = message)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    },
                    actionMenu = { actionMenu(Unit) },
                )
            }
        }
        else -> {}
    }
}

@Composable
fun SentMessagePopup(
    transition: Transition<MessagePopupMode>,
    message: MessageUiModel,
    userDefaultReactions: ImmutableList<String>,
    dismissMenu: () -> Unit,
    onSelectEmoji: (String) -> Unit,
    onShowAllEmojis: () -> Unit,
    onMessageInfo: () -> Unit,
    onReply: (() -> Unit)?,
    onBattle: (() -> Unit)? = null,
    onForward: (() -> Unit)?,
    onCopy: () -> Unit,
    onShare: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val policy = message.messageContent?.actions ?: ActionPolicy.Standard
    val actionMenu = remember(policy, onBattle, message.isPinned) {
        movableContentOf<Unit> {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .wrapContentWidth(),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 4.dp,
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.width(IntrinsicSize.Max)
                ) {
                    ListItemActionNormalIcon(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onMessageInfo,
                        text = stringResource(MR.string.chat_message_info),
                        imageVector = Icons.Default.Info,
                    )
                    if (onReply != null) {
                        ListItemActionNormalIcon(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onReply,
                            text = stringResource(MR.string.chat_message_reply),
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                        )
                    }
                    if (onBattle != null) {
                        ListItemActionNormalIcon(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onBattle,
                            text = stringResource(MR.string.chat_dice_battle_action),
                            imageVector = Icons.Filled.Casino,
                        )
                    }
                    if (onForward != null) {
                        ListItemActionNormalIcon(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onForward,
                            text = stringResource(MR.string.chat_message_forward),
                            imageVector = HomebaseIcons.MessageForward,
                        )
                    }
                    ListItemActionNormalIcon(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCopy,
                        text = stringResource(MR.string.chat_message_copy),
                        imageVector = Icons.Default.ContentCopy,
                    )
                    if (onEdit != null) {
                        ListItemActionNormalIcon(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onEdit,
                            text = stringResource(MR.string.chat_message_edit),
                            imageVector = Icons.Filled.Edit,
                        )
                    }
                    ListItemActionNormalIcon(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onTogglePin,
                        text = stringResource(
                            if (message.isPinned) MR.string.chat_unpin_message
                            else MR.string.chat_pin_message
                        ),
                        imageVector = Icons.Filled.PushPin,
                    )
                    ListItemActionNormalIcon(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDelete,
                        text = stringResource(MR.string.delete),
                        imageVector = Icons.Filled.Delete,
                    )
                    if (isMobile() && onShare != null) {
                        ListItemActionNormalIcon(
                            onClick = onShare,
                            text = stringResource(MR.string.share),
                            imageVector = Icons.Default.Share,
                        )
                    }
                }
            }
        }
    }

    val reactionBar: @Composable (Modifier, (Int) -> Modifier) -> Unit = { background, emoji ->
        ReactionMenu(
            modifier = Modifier.padding(horizontal = 16.dp),
            userDefaultReactions = userDefaultReactions,
            ownReactions = message.ownReactions,
            onSelect = onSelectEmoji,
            onShowAllEmojis = onShowAllEmojis,
            backgroundModifier = background,
            emojiModifier = emoji,
        )
    }
    when (transition.shownMode) {
        MessagePopupMode.Reaction -> {
            // Desktop-only anchor — see ReceivedMessagePopup.
            if (policy.allowInlineReactions && !isMobile()) {
                Popup(
                    onDismissRequest = dismissMenu
                ) {
                    transition.PopupContent { reactionBar(Modifier) { Modifier } }
                }
            }
        }
        MessagePopupMode.Menu -> {
            Popup(
                onDismissRequest = dismissMenu
            ) {
                transition.PopupContent { actionMenu(Unit) }
            }
        }
        MessagePopupMode.All -> {
            PopupWithScrim(
                transition = transition,
                onDismissRequest = dismissMenu
            ) {
                MessageLongPressLayout(
                    alignEnd = true,
                    reactionMenu = reactionBar.takeIf { policy.allowInlineReactions },
                    bubble = {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SentMessageBubbleDisplayOnly(message = message)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    },
                    actionMenu = { actionMenu(Unit) },
                )
            }
        }
        else -> {}
    }
}

enum class MessagePopupMode {
    None,
    All,
    Reaction,
    Menu,
}

// While the popup animates out the target is already None; keep drawing the one that was open.
internal val Transition<MessagePopupMode>.shownMode: MessagePopupMode
    get() = if (targetState != MessagePopupMode.None) targetState else currentState

@Composable
private fun Transition<MessagePopupMode>.PopupContent(
    transformOrigin: TransformOrigin = TransformOrigin.Center,
    content: @Composable () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = { it != MessagePopupMode.None },
        enter = scaleIn(motion.defaultSpatialSpec(), initialScale = 0.8f, transformOrigin = transformOrigin) +
            fadeIn(motion.defaultEffectsSpec()),
        exit = scaleOut(motion.fastSpatialSpec(), targetScale = 0.8f, transformOrigin = transformOrigin) +
            fadeOut(motion.fastEffectsSpec()),
    ) { content() }
}

// Matches Signal's ChatReactionOverlay.kt (strip, staggered emoji) and delay_fade_in / shrink_fade_out (menu).
private fun decelerate(power: Float) = Easing { 1f - (1f - it).pow(power) }

private fun <T> signalReveal(delayMillis: Int) = tween<T>(200, delayMillis, decelerate(2f))

private fun <T> signalHide() = tween<T>(150, easing = decelerate(2f))

// The bubble sits directly above the action menu; the reaction bar sits above both with a gap
// of up to 140dp that the bubble fills. Placed in one pass so the first frame is already final.
@Composable
private fun AnimatedVisibilityScope.MessageLongPressLayout(
    alignEnd: Boolean,
    reactionMenu: (@Composable (background: Modifier, emoji: (Int) -> Modifier) -> Unit)?,
    bubble: @Composable () -> Unit,
    actionMenu: @Composable () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    Layout(
        contents = listOf(
            {
                reactionMenu?.invoke(
                    Modifier.animateEnterExit(
                        enter = fadeIn(signalReveal(100)),
                        exit = fadeOut(signalHide()),
                    ),
                ) { index ->
                    Modifier.animateEnterExit(
                        enter = fadeIn(signalReveal(100 + 10 * index)) +
                            slideInVertically(signalReveal(100 + 10 * index)) { it / 2 },
                        exit = fadeOut(signalHide()) +
                            slideOutVertically(signalHide()) { it / 2 },
                    )
                }
            },
            {
                Box(
                    Modifier.animateEnterExit(
                        enter = scaleIn(motion.defaultSpatialSpec(), initialScale = 0.9f),
                        exit = scaleOut(motion.fastSpatialSpec(), targetScale = 0.9f),
                    ),
                ) { bubble() }
            },
            {
                Box(
                    Modifier.animateEnterExit(
                        enter = fadeIn(signalReveal(150)),
                        exit = scaleOut(
                            tween(220, easing = decelerate(5f)),
                            targetScale = 0.9f,
                            transformOrigin = TransformOrigin(0.5f, 0f),
                        ) + fadeOut(tween(150, easing = decelerate(3f))),
                    ),
                ) { actionMenu() }
            },
        ),
        modifier = Modifier.fillMaxSize(),
    ) { (reactionMeasurables, bubbleMeasurables, actionMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val reaction = reactionMeasurables.firstOrNull()?.measure(loose)
        val bubblePlaceable = bubbleMeasurables.first().measure(loose)
        val action = actionMeasurables.first().measure(loose)
        val gap = if (reaction != null) minOf(bubblePlaceable.height, 140.dp.roundToPx()) else 0
        val columnHeight = (reaction?.height ?: 0) + gap + action.height
        val top = (constraints.maxHeight - columnHeight) / 2
        val actionTop = top + (reaction?.height ?: 0) + gap
        fun Placeable.x() = if (alignEnd) constraints.maxWidth - width else 0
        layout(constraints.maxWidth, constraints.maxHeight) {
            bubblePlaceable.placeRelative(bubblePlaceable.x(), actionTop - bubblePlaceable.height)
            reaction?.placeRelative(reaction.x(), top)
            action.placeRelative(action.x(), actionTop)
        }
    }
}

/**
 * The compact reaction bar, anchored just above the bubble it belongs to. Declare it as a
 * child of the layout that wraps the bubble — [PopupPositionProvider.calculatePosition]
 * receives that parent's bounds as its anchor.
 */
@Composable
fun BubbleReactionPopup(
    transition: Transition<MessagePopupMode>,
    message: MessageUiModel,
    userDefaultReactions: ImmutableList<String>,
    alignToBubbleEnd: Boolean,
    dismissMenu: () -> Unit,
    onSelectEmoji: (String) -> Unit,
    onShowAllEmojis: () -> Unit,
) {
    val policy = message.messageContent?.actions ?: ActionPolicy.Standard
    if (!policy.allowInlineReactions) return
    val growsFromRight = alignToBubbleEnd == (LocalLayoutDirection.current == LayoutDirection.Ltr)
    Popup(
        popupPositionProvider = rememberAboveBubblePositionProvider(alignToBubbleEnd),
        onDismissRequest = dismissMenu,
    ) {
        transition.PopupContent(TransformOrigin(if (growsFromRight) 1f else 0f, 1f)) {
            ReactionMenu(
                modifier = Modifier.padding(horizontal = 16.dp),
                userDefaultReactions = userDefaultReactions,
                ownReactions = message.ownReactions,
                onSelect = onSelectEmoji,
                onShowAllEmojis = onShowAllEmojis,
            )
        }
    }
}

@Composable
private fun rememberAboveBubblePositionProvider(alignToEnd: Boolean): PopupPositionProvider {
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    return remember(gapPx, alignToEnd) { AboveBubblePositionProvider(gapPx, alignToEnd) }
}

internal class AboveBubblePositionProvider(
    private val gapPx: Int,
    private val alignToEnd: Boolean,
    private val windowMarginPx: Int = 0,
    private val flipBelow: Boolean = true,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val alignToRightEdge = alignToEnd == (layoutDirection == LayoutDirection.Ltr)
        val x = if (alignToRightEdge) anchorBounds.right - popupContentSize.width
        else anchorBounds.left
        val maxX = (windowSize.width - popupContentSize.width - windowMarginPx).coerceAtLeast(windowMarginPx)
        val maxY = (windowSize.height - popupContentSize.height - windowMarginPx).coerceAtLeast(windowMarginPx)
        val above = anchorBounds.top - popupContentSize.height - gapPx
        val y = when {
            above >= windowMarginPx -> above
            flipBelow -> (anchorBounds.bottom + gapPx).coerceAtMost(maxY)
            else -> windowMarginPx
        }
        return IntOffset(x.coerceIn(windowMarginPx, maxX), y)
    }
}

@Composable
fun FullScreenMediaMenu(
    showMenu: Boolean,
    dismissMenu: () -> Unit,
    // Null when the host has no way to perform the action — the item is then absent
    // rather than present-but-dead.
    onSave: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onNavigateToMessage: (() -> Unit)? = null,
    // Only set for payloads whose descriptor is ImageFile(isSticker = true). When
    // present, adds a "Save sticker" item that copies the sticker into the user's
    // saved-stickers library. Gated strictly to real stickers so it never appears on
    // ordinary photos (which would create a non-transparent "sticker").
    onSaveSticker: (() -> Unit)? = null,
) {
    DropdownMenu(
        shape = RoundedCornerShape(Dimens.Message.cornerRadius),
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        if (onNavigateToMessage != null) {
            DropdownMenuItem(
                onClick = onNavigateToMessage,
                text = { Text(text = stringResource(MR.string.conversation_media_go_to_message)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                    )
                })
        }
        if (onSave != null) {
            DropdownMenuItem(
                onClick = onSave,
                text = { Text(text = stringResource(MR.string.save)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Download, contentDescription = null)
                })
        }
        if (onSaveSticker != null) {
            DropdownMenuItem(
                onClick = onSaveSticker,
                text = { Text(text = stringResource(MR.string.chat_save_sticker)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Download, contentDescription = null)
                })
        }
        if (onDelete != null) {
            DropdownMenuItem(
                onClick = onDelete,
                text = { Text(text = stringResource(MR.string.delete)) },
                leadingIcon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) })
        }
    }
}

@Composable
fun ConversationListMenu(
    showMenu: Boolean,
    isFilteringUnread: Boolean,
    dismissMenu: () -> Unit,
    onMarkAllAsRead: () -> Unit,
    onFilterUnread: () -> Unit,
    onClearFilterUnread: () -> Unit,
    onSettings: () -> Unit,
) {
    DropdownMenu(
        shape = RoundedCornerShape(Dimens.Message.cornerRadius),
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
//        DropdownMenuItem(
//            onClick = onMarkAllAsRead,
//            text = { Text(text = stringResource(MR.string.chat_mark_all_as_read)) },
//        )
        if (isFilteringUnread) {
            DropdownMenuItem(
                onClick = onClearFilterUnread,
                text = {
                    Text(text = stringResource(MR.string.chat_filter_by_unread_clear_button))
                },
            )
        } else {
            DropdownMenuItem(
                onClick = onFilterUnread,
                text = { Text(text = stringResource(MR.string.chat_filter_by_unread_button)) },
            )
        }
        DropdownMenuItem(
            onClick = onSettings,
            text = { Text(text = stringResource(MR.string.settings)) },
        )
    }
}

@Composable
fun ConversationItemMenuPopup(
    dismissMenu: () -> Unit,
    isPinned: Boolean,
    isArchived: Boolean,
    onMarkAsRead: (() -> Unit)? = null,
    onTogglePin: (() -> Unit)? = null,
    onArchive: () -> Unit,
) {
    Popup(
        onDismissRequest = dismissMenu
    ) {
        Column {
            Surface(
                modifier = Modifier
                    .wrapContentWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 4.dp,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.width(IntrinsicSize.Max)
                ) {
                    if (onMarkAsRead != null) {
                        ListItemActionNormalIcon(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                dismissMenu()
                                onMarkAsRead()
                            },
                            text = stringResource(MR.string.chat_mark_all_as_read),
                            imageVector = Icons.Default.MarkChatRead,
                        )
                    }
                    if (onTogglePin != null) {
                        ListItemActionNormalIcon(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                dismissMenu()
                                onTogglePin()
                            },
                            text = if (isPinned) stringResource(MR.string.chat_unpin) else stringResource(
                                MR.string.chat_pin
                            ),
                            imageVector = Icons.Default.PushPin,
                        )
                    }
                    ListItemActionNormalIcon(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            dismissMenu()
                            onArchive()
                        },
                        text = stringResource(if (isArchived) MR.string.chat_unarchive else MR.string.chat_archive),
                        imageVector = Icons.Default.Archive,
                    )
                }
            }
        }
    }
}

@Composable
private fun PopupWithScrim(
    transition: Transition<MessagePopupMode>,
    onDismissRequest: () -> Unit,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    val motion = MaterialTheme.motionScheme
    Popup(
        onDismissRequest = onDismissRequest
    ) {
        // Each child animates itself, so the scrim fade doesn't multiply into the menus' alpha.
        transition.AnimatedVisibility(
            visible = { it != MessagePopupMode.None },
            enter = EnterTransition.None,
            exit = ExitTransition.None,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .animateEnterExit(
                            enter = fadeIn(motion.defaultEffectsSpec()),
                            exit = fadeOut(motion.fastEffectsSpec()),
                        )
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f))
                        .clickable(
                            onClick = onDismissRequest,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        )
                )
                content()
            }
        }
    }
}
