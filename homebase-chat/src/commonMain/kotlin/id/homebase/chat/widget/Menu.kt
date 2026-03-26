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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
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
import id.homebase.resources.chat_message_copy
import id.homebase.resources.chat_message_edit
import id.homebase.resources.chat_message_forward
import id.homebase.resources.chat_message_info
import id.homebase.resources.chat_message_reply
import id.homebase.resources.chat_pin
import id.homebase.resources.chat_settings
import id.homebase.resources.chat_unarchive
import id.homebase.resources.chat_unpin
import id.homebase.resources.delete
import id.homebase.resources.save
import id.homebase.resources.settings
import id.homebase.resources.share
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConversationMenu(
    showMenu: Boolean,
    dismissMenu: () -> Unit,
    isGroup: Boolean,
    isArchived: Boolean,
    isPinned: Boolean,
    onConversationInfo: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onClear: () -> Unit,
    onIntroduceEveryone: () -> Unit
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
    }
}

@Composable
fun ReceivedMessagePopup(
    mode: MessagePopupMode,
    dismissMenu: () -> Unit,
    onSelectEmoji: (String) -> Unit,
    onShowAllEmojis: () -> Unit,
    onMessageInfo: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    PopupWithScrim(
        onDismissRequest = dismissMenu
    ) {
        Column {
            if (mode == MessagePopupMode.Reaction || mode == MessagePopupMode.All) {
                ReactionMenu(
                    onSelect = onSelectEmoji,
                    onShowAllEmojis = onShowAllEmojis,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (mode == MessagePopupMode.Menu || mode == MessagePopupMode.All) {
                Surface(
                    modifier = Modifier
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
                        ListItemActionNormalIcon(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onReply,
                            text = stringResource(MR.string.chat_message_reply),
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                        )
                        ListItemActionNormalIcon(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onForward,
                            text = stringResource(MR.string.chat_message_forward),
                            imageVector = HomebaseIcons.MessageForward,
                        )
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
                    }
                }
            }
        }
    }
}

@Composable
fun SentMessagePopup(
    mode: MessagePopupMode,
    dismissMenu: () -> Unit,
    onSelectEmoji: (String) -> Unit,
    onShowAllEmojis: () -> Unit,
    onMessageInfo: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    PopupWithScrim(
        onDismissRequest = dismissMenu
    ) {
        // Popup content
        Column {
            Column {
                if (mode == MessagePopupMode.Reaction || mode == MessagePopupMode.All) {
                    ReactionMenu(
                        onSelect = onSelectEmoji,
                        onShowAllEmojis = onShowAllEmojis,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (mode == MessagePopupMode.Menu || mode == MessagePopupMode.All) {
                    Surface(
                        modifier = Modifier
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
                            ListItemActionNormalIcon(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onReply,
                                text = stringResource(MR.string.chat_message_reply),
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                            )
                            ListItemActionNormalIcon(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onForward,
                                text = stringResource(MR.string.chat_message_forward),
                                imageVector = HomebaseIcons.MessageForward,
                            )
                            ListItemActionNormalIcon(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onCopy,
                                text = stringResource(MR.string.chat_message_copy),
                                imageVector = Icons.Default.ContentCopy,
                            )
                            ListItemActionNormalIcon(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onEdit,
                                text = stringResource(MR.string.chat_message_edit),
                                imageVector = Icons.Filled.Edit,
                            )
                            ListItemActionNormalIcon(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onDelete,
                                text = stringResource(MR.string.delete),
                                imageVector = Icons.Filled.Delete,
                            )
                            if (isMobile()) {
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
        }
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
    onDelete: () -> Unit,
    onSave: () -> Unit,
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
        DropdownMenuItem(
            onClick = onDelete,
            text = { Text(text = stringResource(MR.string.delete)) },
            leadingIcon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) })
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
        DropdownMenuItem(
            onClick = onMarkAllAsRead,
            text = { Text(text = stringResource(MR.string.chat_mark_all_as_read)) },
        )
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
                    .background(Color.Black.copy(alpha = 0.5f))
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
