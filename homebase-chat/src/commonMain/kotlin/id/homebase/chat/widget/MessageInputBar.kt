package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.client.link.LinkPreviewProvider
import id.homebase.chat.conversationlist.RecordingData
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.core.util.isMobile
import id.homebase.core.util.keyboardAsState
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_message_attachment_options
import id.homebase.resources.chat_message_edit_message
import id.homebase.resources.chat_message_emoji_options
import id.homebase.resources.chat_message_hide_keyboard
import id.homebase.resources.chat_new_message_placeholder
import id.homebase.resources.chat_send_message_button
import id.homebase.resources.collapse
import id.homebase.resources.expand
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.Clock

private val URL_REGEX = Regex(
    "https?://(?:www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b(?:[-a-zA-Z0-9()@:%_+.~#?&/=]*)"
)

@Composable
fun MessageInputBar(
    modifier: Modifier = Modifier,
    textFieldState: RichTextState,
    recordingData: RecordingData?,
    focusRequester: FocusRequester,
    editExistingMode: Boolean,
    showingEmojiSheet: Boolean,
    onEmojiClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    onFocused: () -> Unit,
    onAddAttachmentClick: () -> Unit,
    onCameraClick: () -> Unit,
    onRecordingStarted: () -> Unit,
    onRecordingStopped: () -> Unit,
    onRecordingCancelled: () -> Unit,
    onRecordingHelp: () -> Unit,
    onSendMessage: (String, LinkPreview?) -> Unit,

    onCancelEdit: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var showExpanded by remember { mutableStateOf(false) }

    val linkPreviewProvider: LinkPreviewProvider = koinInject()
    var linkPreviewData by remember { mutableStateOf<LinkPreview?>(null) }
    var cancelledUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lastFetchedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(textFieldState.annotatedString.text) {
        val text = textFieldState.annotatedString.text
        val match = URL_REGEX.find(text)

        if (match != null) {
            val url = match.value

            // If the user modifies the URL, immediately hide the old preview while we
            // wait to fetch the new one.
            if (linkPreviewData != null && linkPreviewData?.url != url) {
                linkPreviewData = null
            }

            if (url !in cancelledUrls && url != lastFetchedUrl) {
                delay(250)
                lastFetchedUrl = url
                try {
                    val preview = linkPreviewProvider.getLinkPreview(url)
                    // Ensure the URL wasn't cancelled during the network
                    // request delay
                    if (preview != null && url !in cancelledUrls) {
                        linkPreviewData = preview
                    }
                } catch (_: Exception) {
                    // Ignore API errors, but because it's in lastFetchedUrl, we
                    // won't spam retry it on every keystroke
                }
            }
        } else {
            // URL was completely removed, so clean up preview and state
            linkPreviewData = null
            cancelledUrls = emptySet()
            lastFetchedUrl = null
        }
    }

    fun sendMessage() {
        if (textFieldState.annotatedString.isNotBlank()) {
            onSendMessage(textFieldState.toMarkdown(), linkPreviewData)
            linkPreviewData = null
            textFieldState.clear()
        }
    }

    Column(
        modifier = modifier.hoverable(interactionSource),
    ) {
        if (isDesktopOrWeb()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Box(
                    modifier = Modifier.alpha(if (isHovered) 1f else 0f).size(32.dp).clickable(
                        enabled = isHovered,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showExpanded = !showExpanded })
                        .pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (showExpanded) Icons.Default.KeyboardArrowDown
                        else Icons.Default.KeyboardArrowUp,
                        contentDescription = if (showExpanded) stringResource(MR.string.collapse)
                        else stringResource(MR.string.expand),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryFixedVariant
                    )
                }
            }
        }
        if (showExpanded) {
            MessageTextFieldExpanded(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp).focusRequester(focusRequester),
                state = textFieldState,
                linkPreviewData = linkPreviewData,
                onCancelLinkPreview = {
                    linkPreviewData?.let { preview ->
                        cancelledUrls = cancelledUrls + preview.url
                    }
                    linkPreviewData = null
                },
                editExistingMode = editExistingMode,
                onFocused = onFocused,
                onEmojiClick = onEmojiClick,
                onAddAttachmentClick = onAddAttachmentClick,
                sendMessage = {
                    showExpanded = false
                    sendMessage()
                },
                onCancelEdit = onCancelEdit
            )
        } else {
            MessageTextFieldCompact(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp).focusRequester(focusRequester),
                state = textFieldState,
                editExistingMode = editExistingMode,
                linkPreviewData = linkPreviewData,
                recordingData = recordingData,
                onCancelLinkPreview = {
                    linkPreviewData?.let { preview ->
                        cancelledUrls = cancelledUrls + preview.url
                    }
                    linkPreviewData = null
                },
                showingEmojiSheet = showingEmojiSheet,
                onFocused = onFocused,
                onEmojiClick = onEmojiClick,
                onKeyboardClick = onKeyboardClick,
                onAddAttachmentClick = onAddAttachmentClick,
                onCameraClick = onCameraClick,
                onRecordingStarted = onRecordingStarted,
                onRecordingStopped = onRecordingStopped,
                onRecordingCancelled = onRecordingCancelled,
                onRecordingHelp = onRecordingHelp,
                onSendMessage = { sendMessage() },
                onCancelEdit = onCancelEdit
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalRichTextApi::class)
@Composable
fun MessageTextFieldExpanded(
    modifier: Modifier = Modifier,
    state: RichTextState,
    editExistingMode: Boolean,
    linkPreviewData: LinkPreview?,
    onCancelLinkPreview: () -> Unit,
    onEmojiClick: () -> Unit,
    onAddAttachmentClick: () -> Unit,
    onFocused: () -> Unit = {},
    sendMessage: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    Column(modifier = modifier) {
        RichTextEditorButtons(
            modifier = Modifier.fillMaxWidth(),
            state = state,
        )
        if (editExistingMode) {
            MessageEditMessageInfo()
        }
        if (linkPreviewData != null) {
            LinkPreviewCard(
                linkPreview = linkPreviewData,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                isCompact = true,
                onCancel = onCancelLinkPreview
            )
        }
        RichTextEditor(
            state = state,
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        onFocused()
                    }
                },
            placeholder = { Text(stringResource(MR.string.chat_new_message_placeholder)) },
            shape = if (editExistingMode) RoundedCornerShape(
                bottomStart = 12.dp,
                bottomEnd = 12.dp
            ) else RoundedCornerShape(12.dp),
            minLines = 10,
            maxLines = 10,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default
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
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onEmojiClick) {
                Icon(
                    imageVector = Icons.Default.EmojiEmotions, contentDescription = "Emoji"
                )
            }
            if (!editExistingMode) {
                IconButton(
                    onClick = onAddAttachmentClick,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add, contentDescription = stringResource(
                            MR.string.chat_message_attachment_options
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (editExistingMode) {
                IconButton(
                    onClick = onCancelEdit,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(MR.string.cancel),
                    )
                }
            }
            IconButton(
                onClick = { sendMessage() }, colors = IconButtonDefaults.iconButtonColors(
                    containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                    contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                )
            ) {
                Icon(
                    imageVector = if (editExistingMode) Icons.Filled.Check else Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(MR.string.chat_send_message_button),
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
    linkPreviewData: LinkPreview?,
    recordingData: RecordingData?,
    onCancelLinkPreview: () -> Unit,
    editExistingMode: Boolean,
    showingEmojiSheet: Boolean,
    onEmojiClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    onAddAttachmentClick: () -> Unit,
    onCameraClick: () -> Unit,
    onRecordingStarted: () -> Unit,
    onRecordingStopped: () -> Unit,
    onRecordingCancelled: () -> Unit,
    onRecordingHelp: () -> Unit,
    onFocused: () -> Unit = {},
    onSendMessage: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    var isMicrophonePressed by remember { mutableStateOf(false) }
    val micInteractionSource = remember { MutableInteractionSource() }
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(micInteractionSource) {
        var pressStartTime = 0L
        var recordingStartJob: Job? = null

        micInteractionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    isMicrophonePressed = true
                    pressStartTime = Clock.System.now().toEpochMilliseconds()

                    // Trigger haptic feedback on press
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)

                    // Delay starting recording to distinguish from quick tap
                    recordingStartJob = launch {
                        delay(300)
                        onRecordingStarted()
                        Logger.d("Recording started")
                    }
                }

                is PressInteraction.Release -> {
                    isMicrophonePressed = false
                    recordingStartJob?.cancel()

                    val pressDuration = Clock.System.now().toEpochMilliseconds() - pressStartTime
                    if (pressDuration < 200) {
                        // Quick tap - show help
                        Logger.d("Recording help")
                        onRecordingHelp()
                    } else {
                        // Long press - stop recording
                        Logger.d("Recording ended")
                        onRecordingStopped()
                    }
                }

                is PressInteraction.Cancel -> {
                    isMicrophonePressed = false
                    Logger.d("Recording ended")
                    onRecordingStopped()
                }
            }
        }
    }

    Column(
        modifier = modifier
    ) {
        RichTextEditorButtons(
            modifier = Modifier.fillMaxWidth(),
            state = state,
        )
        if (linkPreviewData != null) {
            LinkPreviewCard(
                linkPreview = linkPreviewData,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                isCompact = true,
                onCancel = onCancelLinkPreview
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            val showSendButton = state.annotatedString.isNotBlank()
            if (editExistingMode) {
                IconButton(
                    onClick = onCancelEdit,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(MR.string.cancel),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(
                modifier = Modifier.weight(1f)
            ) {
                if (editExistingMode) {
                    MessageEditMessageInfo()
                }
                RichTextEditor(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                onFocused()
                            }
                        }
                        .onPreviewKeyEvent { keyEvent ->
                            if (isDesktopOrWeb() && keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                                if (keyEvent.isCtrlPressed) {
                                    onSendMessage()
                                    true
                                } else {
                                    false
                                }
                            } else {
                                false
                            }
                        },
                    placeholder = { Text(stringResource(MR.string.chat_new_message_placeholder)) },
                    leadingIcon = {
                        if (!showingEmojiSheet) {
                            IconButton(onClick = onEmojiClick) {
                                Icon(
                                    imageVector = Icons.Default.EmojiEmotions,
                                    contentDescription = stringResource(MR.string.chat_message_emoji_options)
                                )
                            }
                        } else {
                            IconButton(onClick = onKeyboardClick) {
                                Icon(
                                    imageVector = Icons.Default.Keyboard,
                                    contentDescription = stringResource(MR.string.chat_message_emoji_options)
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        if (!editExistingMode) {
                            if (state.annotatedString.isNotBlank()) {
                                IconButton(
                                    onClick = onAddAttachmentClick,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = stringResource(MR.string.chat_message_attachment_options)
                                    )
                                }
                            } else if (isMobile()) {
                                Row {
                                    IconButton(onClick = onCameraClick) {
                                        Icon(
                                            imageVector = Icons.Default.PhotoCamera,
                                            contentDescription = "Camera"
                                        )
                                    }
                                    IconButton(
                                        onClick = {},
                                        interactionSource = micInteractionSource,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Mic,
                                            contentDescription = "Microphone",
                                            tint = if (isMicrophonePressed) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                LocalContentColor.current
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                    shape = if (editExistingMode) RoundedCornerShape(
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    ) else RoundedCornerShape(12.dp),
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
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (showSendButton) {
                IconButton(
                    onClick = onSendMessage, colors = IconButtonDefaults.iconButtonColors(
                        containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                        contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                    ),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Icon(
                        imageVector = if (editExistingMode) Icons.Filled.Check else Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(MR.string.chat_send_message_button),
                    )
                }
            } else if (!editExistingMode) {
                IconButton(
                    onClick = onAddAttachmentClick, colors = IconButtonDefaults.iconButtonColors(
                        containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                        contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                    ),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add, contentDescription = stringResource(
                            MR.string.chat_message_attachment_options
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageEditMessageInfo() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Edit,
            contentDescription = null,
            Modifier.size(14.dp)
        )
        Text(
            text = stringResource(MR.string.chat_message_edit_message),
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageTextFieldForAttachment(
    modifier: Modifier = Modifier,
    state: RichTextState,
    onSmileyClick: () -> Unit,
    onSendMessage: () -> Unit
) {
    val isKeyboardVisible by keyboardAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = modifier) {
        RichTextEditorButtons(
            modifier = Modifier.fillMaxWidth(),
            state = state,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RichTextEditor(
                state = state,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { keyEvent ->
                    if (isDesktopOrWeb() && keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                        if (keyEvent.isCtrlPressed) {
                            onSendMessage()
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                },
                placeholder = {
                    Text(stringResource(MR.string.chat_new_message_placeholder))
                },
                leadingIcon = {
                    IconButton(onClick = onSmileyClick) {
                        Icon(
                            imageVector = Icons.Default.EmojiEmotions,
                            contentDescription = stringResource(
                                MR.string.chat_message_emoji_options
                            )
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
                    capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (!isKeyboardVisible) {
                IconButton(
                    onClick = onSendMessage, colors = IconButtonDefaults.iconButtonColors(
                        containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                        contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(
                            MR.string.chat_send_message_button
                        ),
                    )
                }
            } else {
                IconButton(
                    onClick = { keyboardController?.hide() },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                        contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Check, contentDescription = stringResource(
                            MR.string.chat_message_hide_keyboard
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun RichTextEditorButtons(modifier: Modifier = Modifier, state: RichTextState) {
    LazyRow(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        item {
            RichTextStyleButton(
                onClick = {
                    state.toggleSpanStyle(
                        SpanStyle(fontWeight = FontWeight.Bold)
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
                        SpanStyle(fontStyle = FontStyle.Italic)
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
                        SpanStyle(textDecoration = TextDecoration.Underline)
                    )
                }, isSelected = state.currentSpanStyle.textDecoration?.contains(
                    TextDecoration.Underline
                ) == true, icon = Icons.Outlined.FormatUnderlined
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
                }, isSelected = state.currentSpanStyle.textDecoration?.contains(
                    TextDecoration.LineThrough
                ) == true, icon = Icons.Outlined.FormatStrikethrough
            )
        }

        item { Box(Modifier.height(24.dp).width(1.dp).background(Color(0xFF393B3D))) }

        item {
            RichTextStyleButton(
                onClick = { state.toggleUnorderedList() },
                isSelected = state.isUnorderedList,
                icon = Icons.AutoMirrored.Outlined.FormatListBulleted,
            )
        }

        item {
            RichTextStyleButton(
                onClick = { state.toggleOrderedList() },
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

