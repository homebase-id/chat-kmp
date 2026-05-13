package id.homebase.chat.widget

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
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
import id.homebase.resources.chat_settings
import id.homebase.resources.chat_unarchive
import id.homebase.resources.chat_unpin
import id.homebase.resources.delete
import id.homebase.resources.save
import id.homebase.resources.search
import id.homebase.resources.settings
import id.homebase.resources.share
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

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
    mode: MessagePopupMode,
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
    onBlock: () -> Unit,
    onReport: () -> Unit,
) {
    val policy = message.messageContent?.actions ?: ActionPolicy.Standard
    val actionMenu = remember(policy, onBattle) {
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

    when (mode) {
        MessagePopupMode.Reaction -> {
            // Defensive: this mode is only entered for kinds that allow inline
            // reactions, so the gate is mostly belt-and-braces.
            if (policy.allowInlineReactions) {
                Popup(
                    onDismissRequest = dismissMenu
                ) {
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
        MessagePopupMode.Menu -> {
            Popup(
                onDismissRequest = dismissMenu
            ) {
                actionMenu(Unit)
            }
        }
        MessagePopupMode.All -> {
            PopupWithScrim(
                onDismissRequest = dismissMenu
            ) {
                val localDensity = LocalDensity.current
                var messageBubbleHeight by remember { mutableStateOf(0.dp) }
                var actionMenuY by remember { mutableStateOf(0f) }
                var boxY by remember { mutableStateOf(0f) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            boxY = coordinates.positionInRoot().y
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Layer 1: Message bubble - positioned absolutely, doesn't affect layout
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(horizontal = 16.dp)
                            .offset(
                                y = with(localDensity) {
                                    // Calculate offset relative to Box
                                    (actionMenuY - boxY).toDp() - messageBubbleHeight
                                }
                            )
                            .onGloballyPositioned { coordinates ->
                                messageBubbleHeight = with(localDensity) {
                                    coordinates.size.height.toDp()
                                }
                            }
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ReceivedMessageBubbleDisplayOnly(message = message)
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Layer 2: ReactionMenu (when allowed) + ActionMenu — always centered,
                    // independent of bubble height
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center),
                        horizontalAlignment = Alignment.Start
                    ) {
                        if (policy.allowInlineReactions) {
                            ReactionMenu(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                userDefaultReactions = userDefaultReactions,
                                ownReactions = message.ownReactions,
                                onSelect = onSelectEmoji,
                                onShowAllEmojis = onShowAllEmojis,
                            )

                            Spacer(modifier = Modifier.height(minOf(messageBubbleHeight, 140.dp)))
                        }

                        Box(
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                // Get the Y position of the top of the actionMenu in the Box coordinate space
                                actionMenuY = coordinates.positionInRoot().y
                            }
                        ) {
                            actionMenu(Unit)
                        }
                    }
                }
            }
        }

        else -> {}
    }
}

@Composable
fun SentMessagePopup(
    mode: MessagePopupMode,
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
) {
    val policy = message.messageContent?.actions ?: ActionPolicy.Standard
    val actionMenu = remember(policy, onBattle) {
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

    when (mode) {
        MessagePopupMode.Reaction -> {
            if (policy.allowInlineReactions) {
                Popup(
                    onDismissRequest = dismissMenu
                ) {
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

        MessagePopupMode.Menu -> {
            Popup(
                onDismissRequest = dismissMenu
            ) {
                actionMenu(Unit)
            }
        }

        MessagePopupMode.All -> {
            PopupWithScrim(
                onDismissRequest = dismissMenu
            ) {
                val localDensity = LocalDensity.current
                var messageBubbleHeight by remember { mutableStateOf(0.dp) }
                var actionMenuY by remember { mutableStateOf(0f) }
                var boxY by remember { mutableStateOf(0f) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            boxY = coordinates.positionInRoot().y
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Layer 1: Message bubble - positioned absolutely, doesn't affect layout
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(horizontal = 16.dp)
                            .offset(
                                y = with(localDensity) {
                                    // Calculate offset relative to Box
                                    (actionMenuY - boxY).toDp() - messageBubbleHeight
                                }
                            )
                            .onGloballyPositioned { coordinates ->
                                messageBubbleHeight = with(localDensity) {
                                    coordinates.size.height.toDp()
                                }
                            }
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        SentMessageBubbleDisplayOnly(message = message)
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Layer 2: ReactionMenu (when allowed) + ActionMenu — always centered,
                    // independent of bubble height
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center),
                        horizontalAlignment = Alignment.End
                    ) {
                        if (policy.allowInlineReactions) {
                            ReactionMenu(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                userDefaultReactions = userDefaultReactions,
                                ownReactions = message.ownReactions,
                                onSelect = onSelectEmoji,
                                onShowAllEmojis = onShowAllEmojis,
                            )

                            Spacer(modifier = Modifier.height(minOf(messageBubbleHeight, 140.dp)))
                        }

                        Box(
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                // Get the Y position of the top of the actionMenu in the Box coordinate space
                                actionMenuY = coordinates.positionInRoot().y
                            }
                        ) {
                            actionMenu(Unit)
                        }
                    }
                }
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

@Composable
fun FullScreenMediaMenu(
    showMenu: Boolean,
    dismissMenu: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    DropdownMenu(
        shape = RoundedCornerShape(Dimens.Message.cornerRadius),
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        DropdownMenuItem(
            onClick = onSave,
            text = { Text(text = stringResource(MR.string.save)) },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Download, contentDescription = null)
            })
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
fun PopupWithScrim(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    Popup(
        onDismissRequest = onDismissRequest
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Scrim/Dimmed background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
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
