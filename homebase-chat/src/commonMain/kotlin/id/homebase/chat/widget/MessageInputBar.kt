package id.homebase.chat.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.resources.MR
import id.homebase.resources.chat_new_message_placeholder
import org.jetbrains.compose.resources.stringResource

@Composable
fun MessageInputBar(
    modifier: Modifier = Modifier,
    onSmileyClick: () -> Unit = {},
    onPlusClick: () -> Unit = {},
    onSendMessage: (String) -> Unit = {}
) {
    val textFieldState = rememberTextFieldState()
    var showDropdownMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var showExpanded by remember { mutableStateOf(false) }

    fun sendMessage() {
        if (textFieldState.text.isNotBlank()) {
            onSendMessage(textFieldState.text.toString())
            textFieldState.setTextAndPlaceCursorAtEnd("")
        }
    }

    Column(
        modifier = Modifier.hoverable(interactionSource),
    ) {
        if (isDesktopOrWeb()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .alpha(if (isHovered) 1f else 0f)
                        .size(32.dp)
                        .clickable(
                            enabled = isHovered,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                showExpanded = !showExpanded
                            }
                        )
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (showExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                    )
                }
            }
        }
        if (showExpanded) {
            MessageTextFieldExpanded(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                textFieldState = textFieldState,
            )
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = onSmileyClick) {
                    Icon(
                        imageVector = Icons.Default.EmojiEmotions,
                        contentDescription = "Emoji"
                    )
                }
                Box {
                    IconButton(
                        onClick = {
                            showDropdownMenu = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add attachment"
                        )
                    }

                    if (showDropdownMenu) {
                        DropdownMenu(
                            expanded = showDropdownMenu,
                            onDismissRequest = { showDropdownMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Photo") },
                                onClick = {
                                    showDropdownMenu = false
                                    onPlusClick()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Photo, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("File") },
                                onClick = {
                                    showDropdownMenu = false
                                    onPlusClick()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.AttachFile, contentDescription = null)
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        showExpanded = false
                        sendMessage()
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                    )
                }
            }
        } else {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val showSendButton = !isDesktopOrWeb() && textFieldState.text.isNotBlank()

                MessageTextFieldCompact(
                    modifier = Modifier.weight(1f),
                    textFieldState = textFieldState,
                    onSmileyClick = onSmileyClick,
                    onMediaClick = onPlusClick,
                    onFileClick = onPlusClick,
                    onCameraClick = onPlusClick,
                    onSendMessage = { sendMessage() },
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (showSendButton) {
                    IconButton(
                        onClick = { sendMessage() },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message",
                        )
                    }
                } else {
                    AddAttachmentIcon(
                        onMediaClick = onPlusClick,
                        onFileClick = onPlusClick,
                    )
                }
            }
        }
    }
}

@Composable
fun MessageTextFieldExpanded(
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState,
) {
    TextField(
        state = textFieldState,
        modifier = modifier,
        placeholder = { Text(stringResource(MR.string.chat_new_message_placeholder)) },
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        lineLimits = TextFieldLineLimits.MultiLine(
            minHeightInLines = 10,
            maxHeightInLines = 10
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Default
        )
    )
}

@Composable
fun MessageTextFieldCompact(
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState,
    onSmileyClick: () -> Unit,
    onMediaClick: () -> Unit,
    onFileClick: () -> Unit,
    onCameraClick: () -> Unit,
    onSendMessage: () -> Unit
) {
    TextField(
        state = textFieldState,
        modifier = modifier
            .onPreviewKeyEvent { keyEvent ->
                if (isDesktopOrWeb() && keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                    if (keyEvent.isShiftPressed) {
                        // Manually insert newline for Shift+Enter
                        textFieldState.setTextAndPlaceCursorAtEnd(textFieldState.text.toString() + "\n")
                        true
                    } else {
                        // Regular Enter: send message
                        onSendMessage()
                        true
                    }
                } else {
                    false
                }
            },
        placeholder = { Text(stringResource(MR.string.chat_new_message_placeholder)) },
        leadingIcon = {
            IconButton(onClick = onSmileyClick) {
                Icon(
                    imageVector = Icons.Default.EmojiEmotions,
                    contentDescription = "Emoji"
                )
            }
        },
        trailingIcon = {
            if (!isDesktopOrWeb() && textFieldState.text.isNotBlank()) {
                AddAttachmentIcon(
                    onMediaClick = onMediaClick,
                    onFileClick = onFileClick,
                )
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        lineLimits = TextFieldLineLimits.MultiLine(
            minHeightInLines = 1,
            maxHeightInLines = 3
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Default
        )
    )
}

@Composable
fun AddAttachmentIcon(
    onMediaClick: () -> Unit,
    onFileClick: () -> Unit,
) {
    Box {
        var showDropdownMenu by remember { mutableStateOf(false) }
        IconButton(
            onClick = {
                // TODO - handle different on mobile
                showDropdownMenu = true
            }
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add attachment"
            )
        }

        if (showDropdownMenu) {
            DropdownMenu(
                expanded = showDropdownMenu,
                onDismissRequest = { showDropdownMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Photo") },
                    onClick = {
                        showDropdownMenu = false
                        onMediaClick()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Photo, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("File") },
                    onClick = {
                        showDropdownMenu = false
                        onFileClick()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.AttachFile, contentDescription = null)
                    }
                )
            }
        }
    }
}