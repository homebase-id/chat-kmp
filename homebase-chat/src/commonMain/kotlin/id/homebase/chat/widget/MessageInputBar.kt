package id.homebase.chat.widget
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
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
    val textFieldState = rememberRichTextState()
    //textFieldState.config.linkColor = Color.Blue
    //textFieldState.config.linkTextDecoration = TextDecoration.Underline
    //textFieldState.config.codeSpanColor = Color.Blue
    //textFieldState.config.codeSpanBackgroundColor = Color.Magenta
    //textFieldState.config.codeSpanStrokeColor = Color.Yellow
    textFieldState.config.listIndent = 0

    LaunchedEffect(Unit) {
        // TODO - restored stored draft here
        textFieldState.clear()
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var showExpanded by remember { mutableStateOf(false) }

    fun sendMessage() {
        if (textFieldState.annotatedString.isNotBlank()) {
            onSendMessage(textFieldState.toHtml())
            textFieldState.clear()
        }
    }

    Column(
        modifier = modifier.hoverable(interactionSource),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                state = textFieldState,
                onSmileyClick = onSmileyClick,
                onPlusClick = onPlusClick,
                sendMessage = {
                    showExpanded = false
                    sendMessage()
                }
            )
        } else {
            MessageTextFieldCompact(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                state = textFieldState,
                onSmileyClick = onSmileyClick,
                onMediaClick = onPlusClick,
                onFileClick = onPlusClick,
                onCameraClick = onPlusClick,
                onSendMessage = { sendMessage() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalRichTextApi::class)
@Composable
fun MessageTextFieldExpanded(
    modifier: Modifier = Modifier,
    state: RichTextState,
    onSmileyClick: () -> Unit,
    onPlusClick: () -> Unit,
    sendMessage: () -> Unit
) {
    var showDropdownMenu by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
    ) {
        RichTextEditorButtons(
            modifier = Modifier.fillMaxWidth(),
            state = state,
        )
        RichTextEditor(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(MR.string.chat_new_message_placeholder)) },
            shape = RoundedCornerShape(12.dp),
            minLines = 10,
            maxLines = 10,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default
            ),
            colors = RichTextEditorDefaults.richTextEditorColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth(),
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageTextFieldCompact(
    modifier: Modifier = Modifier,
    state: RichTextState,
    onSmileyClick: () -> Unit,
    onMediaClick: () -> Unit,
    onFileClick: () -> Unit,
    onCameraClick: () -> Unit,
    onSendMessage: () -> Unit
) {
    Column(
        modifier = modifier
    ) {
        RichTextEditorButtons(
            modifier = Modifier.fillMaxWidth(),
            state = state,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val showSendButton = state.annotatedString.isNotBlank()
            RichTextEditor(
                state = state,
                modifier = Modifier.weight(1f)
                    .onPreviewKeyEvent { keyEvent ->
                        if (isDesktopOrWeb() && keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                            if (keyEvent.isShiftPressed) {
                                // Manually insert newline for Shift+Enter
                                state.setText(state.toText() + "\n")
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
                    if (!isDesktopOrWeb() && state.annotatedString.isNotBlank()) {
                        AddAttachmentIcon(
                            onMediaClick = onMediaClick,
                            onFileClick = onFileClick,
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = RichTextEditorDefaults.richTextEditorColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                minLines = 1,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (showSendButton) {
                IconButton(
                    onClick = onSendMessage,
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
                    onMediaClick = onMediaClick,
                    onFileClick = onFileClick,
                )
            }
        }
    }
}

@Composable
fun RichTextEditorButtons(
    modifier: Modifier = Modifier,
    state: RichTextState
) {
    LazyRow(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        item {
            RichTextStyleButton(
                onClick = {
                    state.toggleSpanStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold
                        )
                    )
                },
                isSelected = state.currentSpanStyle.fontWeight == FontWeight.Bold,
                icon = Icons.Outlined.FormatBold
            )
        }

        item {
            RichTextStyleButton(
                onClick = {
                    state.toggleSpanStyle(
                        SpanStyle(
                            fontStyle = FontStyle.Italic
                        )
                    )
                },
                isSelected = state.currentSpanStyle.fontStyle == FontStyle.Italic,
                icon = Icons.Outlined.FormatItalic
            )
        }

        item {
            RichTextStyleButton(
                onClick = {
                    state.toggleSpanStyle(
                        SpanStyle(
                            textDecoration = TextDecoration.Underline
                        )
                    )
                },
                isSelected = state.currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) == true,
                icon = Icons.Outlined.FormatUnderlined
            )
        }

        item {
            RichTextStyleButton(
                onClick = {
                    state.toggleSpanStyle(
                        SpanStyle(
                            textDecoration = TextDecoration.LineThrough
                        )
                    )
                },
                isSelected = state.currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) == true,
                icon = Icons.Outlined.FormatStrikethrough
            )
        }

        item {
            Box(
                Modifier
                    .height(24.dp)
                    .width(1.dp)
                    .background(Color(0xFF393B3D))
            )
        }

        item {
            RichTextStyleButton(
                onClick = {
                    state.toggleUnorderedList()
                },
                isSelected = state.isUnorderedList,
                icon = Icons.AutoMirrored.Outlined.FormatListBulleted,
            )
        }

        item {
            RichTextStyleButton(
                onClick = {
                    state.toggleOrderedList()
                },
                isSelected = state.isOrderedList,
                icon = Icons.Outlined.FormatListNumbered,
            )
        }

//            item {
//                Box(
//                    Modifier
//                        .height(24.dp)
//                        .width(1.dp)
//                        .background(Color(0xFF393B3D))
//                )
//            }
//            item {
//                RichTextStyleButton(
//                    onClick = {
//                        state.toggleCodeSpan()
//                    },
//                    isSelected = state.isCodeSpan,
//                    icon = Icons.Outlined.Code,
//                )
//            }
    }
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
