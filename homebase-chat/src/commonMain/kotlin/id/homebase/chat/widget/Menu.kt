package id.homebase.chat.widget

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.util.isMobile
import id.homebase.resources.MR
import id.homebase.resources.chat_archive
import id.homebase.resources.chat_clear
import id.homebase.resources.chat_delete
import id.homebase.resources.chat_filter_by_unread_button
import id.homebase.resources.chat_filter_by_unread_clear_button
import id.homebase.resources.chat_group_settings
import id.homebase.resources.chat_mark_all_as_read
import id.homebase.resources.chat_message_edit
import id.homebase.resources.chat_message_info
import id.homebase.resources.chat_message_reply
import id.homebase.resources.chat_settings
import id.homebase.resources.delete
import id.homebase.resources.save
import id.homebase.resources.settings
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
) {
    DropdownMenu(
        shape = RoundedCornerShape(Dimens.Message.cornerRadius),
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        DropdownMenuItem(onClick = onConversationInfo, text = {
            Text(
                text = if (isGroup) stringResource(MR.string.chat_group_settings)
                else stringResource(MR.string.chat_settings)
            )
        }, leadingIcon = { Icon(imageVector = Icons.Default.Info, contentDescription = null) })

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
    }
}

@Composable
fun ReceivedMessageMenu(
    showMenu: Boolean,
    dismissMenu: () -> Unit,
    onMessageInfo: () -> Unit,
    onReply: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onMarkAsRead: () -> Unit,
) {
    DropdownMenu(
        shape = RoundedCornerShape(Dimens.Message.cornerRadius),
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        DropdownMenuItem(
            onClick = onMessageInfo,
            text = { Text(text = stringResource(MR.string.chat_message_info)) },
            leadingIcon = { Icon(imageVector = Icons.Default.Info, contentDescription = null) })

        HorizontalDivider()

        DropdownMenuItem(
            onClick = onReply,
            text = { Text(text = stringResource(MR.string.chat_message_reply)) },
            leadingIcon = {
                Icon(imageVector = Icons.AutoMirrored.Filled.Reply, contentDescription = null)
            })

        DropdownMenuItem(
            onClick = onDelete,
            text = { Text(text = stringResource(MR.string.delete)) },
            leadingIcon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) })

        if (isMobile()) {
            DropdownMenuItem(onClick = onShare, text = { Text(text = "Share") }, leadingIcon = {
                Icon(imageVector = Icons.Default.Share, contentDescription = null)
            })
        }

        HorizontalDivider()

        DropdownMenuItem(
            onClick = onMarkAsRead,
            text = { Text("mark as read") },
            leadingIcon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) })
    }
}

@Composable
fun SentMessageMenu(
    showMenu: Boolean,
    dismissMenu: () -> Unit,
    onMessageInfo: () -> Unit,
    onReply: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(
        shape = RoundedCornerShape(Dimens.Message.cornerRadius),
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        DropdownMenuItem(
            onClick = onMessageInfo,
            text = { Text(text = stringResource(MR.string.chat_message_info)) },
            leadingIcon = { Icon(imageVector = Icons.Default.Info, contentDescription = null) })

        HorizontalDivider()

        DropdownMenuItem(
            onClick = onReply,
            text = { Text(text = stringResource(MR.string.chat_message_reply)) },
            leadingIcon = {
                Icon(imageVector = Icons.AutoMirrored.Filled.Reply, contentDescription = null)
            })
        DropdownMenuItem(
            onClick = onEdit,
            text = { Text(text = stringResource(MR.string.chat_message_edit)) },
            leadingIcon = { Icon(imageVector = Icons.Filled.Edit, contentDescription = null) })
        DropdownMenuItem(
            onClick = onDelete,
            text = { Text(text = stringResource(MR.string.delete)) },
            leadingIcon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) })

        if (isMobile()) {
            DropdownMenuItem(onClick = onShare, text = { Text(text = "Share") }, leadingIcon = {
                Icon(imageVector = Icons.Default.Share, contentDescription = null)
            })
        }
    }
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
