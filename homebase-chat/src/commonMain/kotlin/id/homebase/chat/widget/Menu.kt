package id.homebase.chat.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
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
import id.homebase.resources.chat_message_info
import id.homebase.resources.chat_message_reply
import id.homebase.resources.chat_settings
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
    onConversationInfo: () -> Unit,
    onDelete: () -> Unit,
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
            onClick = onArchive,
            text = { Text(text = stringResource(MR.string.chat_archive)) },
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
    onMarkAsRead: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Popup(
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
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 4.dp,
                    tonalElevation = 2.dp
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
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Popup(
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
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 4.dp,
                    tonalElevation = 2.dp
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
