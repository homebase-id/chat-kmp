package id.homebase.chat.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ConversationMenu(
    showMenu: Boolean,
    conversationId: String,
    onDelete: (conversationId: String) -> Unit,
    dismissMenu: () -> Unit,
) {
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Menu above the fold") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = null
                )
            }
        )

        HorizontalDivider()


        DropdownMenuItem(
            onClick = {
                onDelete(conversationId)
                dismissMenu()
            },
            text = { Text(text = "Delete") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null
                )
            }
        )
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Block") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null
                )
            }
        )

    }
}

@Composable
fun ReceivedMessageMenu(
    showMenu: Boolean,
    messageId: String,
    onDelete: (messageId: String) -> Unit,
    dismissMenu: () -> Unit,
) {
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Menu above the fold") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = null
                )
            }
        )

        HorizontalDivider()


        DropdownMenuItem(
            onClick = {
                onDelete(messageId)
                dismissMenu()
            },
            text = { Text(text = "Delete") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null
                )
            }
        )
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Block") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null
                )
            }
        )

    }
}

@Composable
fun SentMessageMenu(
    showMenu: Boolean,
    messageId: String,
    onDelete: (messageId: String) -> Unit,
    dismissMenu: () -> Unit,
) {
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = dismissMenu
    ) {
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Menu above the fold") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = null
                )
            }
        )

        HorizontalDivider()


        DropdownMenuItem(
            onClick = {
                onDelete(messageId)
                dismissMenu()
            },
            text = { Text(text = "Delete") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null
                )
            }
        )
        DropdownMenuItem(
            onClick = {
                dismissMenu()
            },
            text = { Text(text = "Block") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null
                )
            }
        )

    }
}