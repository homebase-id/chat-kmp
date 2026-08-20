package id.homebase.chat.widget

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import id.homebase.api.client.link.LinkPreviewProvider
import id.homebase.chat.conversationlist.RecordingData
import id.homebase.chat.conversationlist.shouldSendComposerMessage
import id.homebase.chat.services.renderer.PayloadRenderer
import id.homebase.chat.services.renderer.LinkPreviewRenderer
import id.homebase.core.audio.rememberRecordAudioPermissionState
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.rememberHaptics
import id.homebase.core.clipboard.clipboardImageReceiverModifier
import id.homebase.core.clipboard.getImageFromClipboard
import id.homebase.core.clipboard.pasteImageContextMenuItem
import id.homebase.core.clipboard.readClipboardImage
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.core.util.isMobile
import id.homebase.core.util.keyboardAsState
import id.homebase.core.util.toMessageMarkdown
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_message_attachment_options
import id.homebase.resources.chat_message_camera
import id.homebase.resources.chat_message_edit_message
import id.homebase.resources.chat_message_emoji
import id.homebase.resources.chat_message_emoji_options
import id.homebase.resources.chat_message_hide_keyboard
import id.homebase.resources.chat_message_microphone
import id.homebase.resources.chat_message_paste_image
import id.homebase.resources.chat_message_processing
import id.homebase.resources.chat_message_record_video
import id.homebase.resources.chat_markdown_blockquote
import id.homebase.resources.chat_markdown_code_block
import id.homebase.resources.chat_markdown_heading
import id.homebase.resources.chat_markdown_inline_code
import id.homebase.resources.chat_markdown_link
import id.homebase.resources.chat_markdown_link_dialog_cancel
import id.homebase.resources.chat_markdown_link_dialog_insert
import id.homebase.resources.chat_markdown_link_dialog_text
import id.homebase.resources.chat_markdown_link_dialog_title
import id.homebase.resources.chat_markdown_link_dialog_url
import id.homebase.resources.chat_markdown_nested_list
import id.homebase.resources.chat_message_take_photo
import id.homebase.resources.chat_new_message_placeholder
import id.homebase.resources.chat_send_message_button
import id.homebase.resources.collapse
import id.homebase.resources.expand
import id.homebase.resources.slide_to_cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private enum class StandaloneFabAction { Confirm, Send, Attach }

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
    isSendingMessage: Boolean = false,
    showActionButtons: Boolean = true,
    onSendStateChanged: ((isSendable: Boolean) -> Unit)? = null,
    onRecordingStateChanged: ((isRecording: Boolean) -> Unit)? = null,
    onEmojiClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    onFocused: () -> Unit,
    onAddAttachmentClick: () -> Unit,
    onCameraClick: () -> Unit,
    onVideoRecordClick: () -> Unit,
    onRecordingStarted: () -> Unit,
    onRecordingStopped: () -> Unit,
    onRecordingCancelled: () -> Unit,
    onRecordingHelp: () -> Unit,
    payloadRenderers: List<PayloadRenderer>,
    onPayloadRenderersChange: (List<PayloadRenderer>) -> Unit,
    onSendMessage: (String, List<PayloadRenderer>) -> Unit,
    onPasteImage: ((ByteArray) -> Unit)? = null,
    onCancelEdit: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var showExpanded by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

    // URL-detector private state. Lives here (not in the parent) because it's an
    // implementation detail of how LinkPreviewRenderer gets produced from typed text:
    //  - cancelledUrls: URLs the user explicitly dismissed; don't auto-refetch.
    //  - lastFetchedUrl: debounce memory; don't spam refetch on every keystroke.
    val linkPreviewProvider: LinkPreviewProvider = koinInject()
    var cancelledUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lastFetchedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(textFieldState.annotatedString.text) {
        val text = textFieldState.annotatedString.text
        val match = URL_REGEX.find(text)

        if (match != null) {
            val url = match.value

            // If the user modifies the URL, immediately hide the old preview while we
            // wait to fetch the new one.
            val existingLink = payloadRenderers.firstOrNull { it is LinkPreviewRenderer } as? LinkPreviewRenderer
            if (existingLink != null && existingLink.preview.url != url) {
                onPayloadRenderersChange(payloadRenderers.filterNot { it is LinkPreviewRenderer })
            }

            if (url !in cancelledUrls && url != lastFetchedUrl) {
                delay(250)
                lastFetchedUrl = url
                try {
                    val preview = linkPreviewProvider.getLinkPreview(url)
                    // Ensure the URL wasn't cancelled during the network request delay.
                    if (preview != null && url !in cancelledUrls) {
                        onPayloadRenderersChange(
                            payloadRenderers.filterNot { it is LinkPreviewRenderer } +
                                LinkPreviewRenderer(preview)
                        )
                    }
                } catch (_: Exception) {
                    // Ignore API errors, but because it's in lastFetchedUrl, we
                    // won't spam retry it on every keystroke.
                }
            }
        } else {
            // URL was completely removed, so clean up preview and reset memory.
            if (payloadRenderers.any { it is LinkPreviewRenderer }) {
                onPayloadRenderersChange(payloadRenderers.filterNot { it is LinkPreviewRenderer })
            }
            cancelledUrls = emptySet()
            lastFetchedUrl = null
        }
    }

    /**
     * Cancel-X button on a staged attachment card. For link previews, also remembers the URL so
     * the URL-detector won't re-stage it on the next keystroke (preserves the original behaviour
     * of `cancelledUrls`).
     */
    fun cancelRenderer(id: String) {
        val att = payloadRenderers.firstOrNull { it.id == id } ?: return
        if (att is LinkPreviewRenderer) {
            cancelledUrls = cancelledUrls + att.preview.url
        }
        onPayloadRenderersChange(payloadRenderers.filterNot { it.id == id })
    }

    fun sendMessage() {
        // Gate on the NORMALIZED serialized body — the exact value that gets sent.
        // toMessageMarkdown() strips richeditor's `<br>` empty-paragraph artifacts, so a stray
        // blank line (which serializes to a non-blank `"\n<br>"`) no longer slips past the gate as
        // a blank/`<br>` message (#1104). shouldSendComposerMessage encodes the "link previews
        // alone don't send" policy (user-initiated kinds like location DO).
        val markdown = textFieldState.toMessageMarkdown()
        if (!isSendingMessage && shouldSendComposerMessage(markdown, payloadRenderers)) {
            haptics.perform(HapticEvent.Confirm)
            onSendMessage(markdown, payloadRenderers)
            // Don't clear here — the ViewModel clears after the send is queued,
            // so the text stays in the edit box if the send fails.
        }
    }

    Column(
        modifier = modifier.hoverable(interactionSource),
    ) {
        if (isDesktopOrWeb()) {
            AnimatedVisibility(
                visible = isHovered,
                enter = expandVertically(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(150)),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Box(
                        modifier = Modifier.size(32.dp).clickable(
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
        }
        if (showExpanded) {
            MessageTextFieldExpanded(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                focusRequester = focusRequester,
                state = textFieldState,
                payloadRenderers = payloadRenderers,
                onCancelAttachment = ::cancelRenderer,
                editExistingMode = editExistingMode,
                onFocused = onFocused,
                onEmojiClick = onEmojiClick,
                onAddAttachmentClick = onAddAttachmentClick,
                onPasteImage = onPasteImage,
                sendMessage = {
                    showExpanded = false
                    sendMessage()
                },
                onCancelEdit = onCancelEdit
            )
        } else {
            MessageTextFieldCompact(
                modifier = Modifier.fillMaxWidth().then(
                    if (showActionButtons) Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    else Modifier
                ),
                focusRequester = focusRequester,
                state = textFieldState,
                editExistingMode = editExistingMode,
                payloadRenderers = payloadRenderers,
                recordingData = recordingData,
                onCancelAttachment = ::cancelRenderer,
                showingEmojiSheet = showingEmojiSheet,
                onFocused = onFocused,
                onEmojiClick = onEmojiClick,
                onKeyboardClick = onKeyboardClick,
                onAddAttachmentClick = onAddAttachmentClick,
                onCameraClick = onCameraClick,
                onVideoRecordClick = onVideoRecordClick,
                onRecordingStarted = onRecordingStarted,
                onRecordingStopped = onRecordingStopped,
                onRecordingCancelled = onRecordingCancelled,
                onRecordingHelp = onRecordingHelp,
                onPasteImage = onPasteImage,
                isSendingMessage = isSendingMessage,
                showActionButtons = showActionButtons,
                onSendStateChanged = onSendStateChanged,
                onRecordingStateChanged = onRecordingStateChanged,
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
    focusRequester: FocusRequester,
    state: RichTextState,
    editExistingMode: Boolean,
    payloadRenderers: List<PayloadRenderer>,
    onCancelAttachment: (id: String) -> Unit,
    onEmojiClick: () -> Unit,
    onAddAttachmentClick: () -> Unit,
    onPasteImage: ((ByteArray) -> Unit)? = null,
    onFocused: () -> Unit = {},
    sendMessage: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    val pasteScope = rememberCoroutineScope()
    Column(modifier = modifier) {
        RichTextEditorButtons(
            modifier = Modifier.fillMaxWidth(),
            state = state,
            enabled = true,
        )
        if (editExistingMode) {
            MessageEditMessageInfo(
                showingEmojiSheet = false,
                showExtraButtons = false,
                onEmojiClick = onEmojiClick,
                onKeyboardClick = {}
            )
        }
        payloadRenderers.forEach { att ->
            PayloadRendererRow(
                attachment = att,
                onCancel = { onCancelAttachment(att.id) },
            )
        }
        val pasteModifier = if (onPasteImage != null) {
            clipboardImageReceiverModifier { bytes -> onPasteImage.invoke(bytes) }
        } else {
            Modifier
        }
        val pasteImageLabel = stringResource(MR.string.chat_message_paste_image)
        RichTextEditor(
            state = state,
            modifier = Modifier.fillMaxWidth()
                .then(pasteModifier)
                .then(
                    if (onPasteImage != null)
                        Modifier.pasteImageContextMenuItem(
                            label = pasteImageLabel,
                            enabled = true,
                        ) {
                            pasteScope.launch {
                                readClipboardImage()?.let { onPasteImage.invoke(it) }
                            }
                        }
                    else Modifier
                )
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { keyEvent ->
                    // Cmd/Ctrl+V image paste works on any platform with a hardware
                    // keyboard — desktop, web, AND iOS/iPad. Enter-to-send (below)
                    // stays desktop/web only; mobile uses the send button.
                    if (keyEvent.type == KeyEventType.KeyDown &&
                        (isDesktopOrWeb() ||
                            (keyEvent.key == Key.V && (keyEvent.isCtrlPressed || keyEvent.isMetaPressed)))
                    ) {
                        when {
                            // Shift+Enter inserts a newline; every other Enter/NumPadEnter
                            // (incl. Cmd/Ctrl+Enter) sends. Match NumPadEnter too — macOS can
                            // report Return as NumPadEnter, so Key.Enter alone never fired (#1043).
                            (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter) &&
                                keyEvent.isShiftPressed -> {
                                state.addTextAfterSelection("\n")
                                true
                            }

                            keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter -> {
                                sendMessage()
                                true
                            }

                            keyEvent.key == Key.V && (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) && onPasteImage != null -> {
                                val imageBytes = getImageFromClipboard()
                                if (imageBytes != null) {
                                    onPasteImage.invoke(imageBytes)
                                    true
                                } else {
                                    // The browser clipboard is async-only, so the read above
                                    // always returns null on web. Start the async read and
                                    // report the event unhandled: consuming it would swallow
                                    // an ordinary text paste, and we cannot know yet whether
                                    // the clipboard holds an image.
                                    pasteScope.launch {
                                        readClipboardImage()?.let { onPasteImage.invoke(it) }
                                    }
                                    false
                                }
                            }

                            else -> false
                        }
                    } else {
                        false
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
                    imageVector = Icons.Default.EmojiEmotions, contentDescription = stringResource(MR.string.chat_message_emoji)
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
                onClick = { sendMessage() },
                colors = IconButtonDefaults.iconButtonColors(
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
    focusRequester: FocusRequester,
    state: RichTextState,
    payloadRenderers: List<PayloadRenderer>,
    recordingData: RecordingData?,
    onCancelAttachment: (id: String) -> Unit,
    editExistingMode: Boolean,
    showingEmojiSheet: Boolean,
    onEmojiClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    onAddAttachmentClick: () -> Unit,
    onCameraClick: () -> Unit,
    onVideoRecordClick: () -> Unit,
    onRecordingStarted: () -> Unit,
    onRecordingStopped: () -> Unit,
    onRecordingCancelled: () -> Unit,
    onRecordingHelp: () -> Unit,
    onPasteImage: ((ByteArray) -> Unit)? = null,
    onFocused: () -> Unit = {},
    isSendingMessage: Boolean = false,
    showActionButtons: Boolean = true,
    onSendStateChanged: ((isSendable: Boolean) -> Unit)? = null,
    onRecordingStateChanged: ((isRecording: Boolean) -> Unit)? = null,
    onSendMessage: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    val pasteScope = rememberCoroutineScope()
    // Send button is shown when there's text OR a user-initiated attachment (not link previews,
    // which are auto-detected from typed URLs and don't on their own indicate intent to send).
    val showSendButton = state.annotatedString.isNotBlank() ||
        payloadRenderers.any { it !is LinkPreviewRenderer }
    val showRecordingButton by remember(
        editExistingMode,
        showSendButton
    ) { derivedStateOf { !editExistingMode && !showSendButton } }
    var isMicrophonePressed by remember { mutableStateOf(false) }
    var isRecordingActive by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var dragOffset by remember { mutableStateOf(0f) }
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { 200.dp.toPx() }
    var isKeyboardFocused by remember { mutableStateOf(false) }

    LaunchedEffect(showSendButton) {
        onSendStateChanged?.invoke(showSendButton)
    }
    LaunchedEffect(isRecordingActive) {
        onRecordingStateChanged?.invoke(isRecordingActive)
    }

    val recordAudioPermissionState = rememberRecordAudioPermissionState(
        onPermissionGranted = {
            Logger.d("Record audio permission granted")
        }
    )

    val micButtonSize by animateDpAsState(
        targetValue = if (showActionButtons) {
            if (isMicrophonePressed) 72.dp else 56.dp
        } else {
            40.dp
        },
        animationSpec = tween(durationMillis = if (showActionButtons) 1000 else 300),
        label = "micButtonSize"
    )
    val micButtonColor by animateColorAsState(
        targetValue = if (isMicrophonePressed) Color.Red else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(durationMillis = if (showActionButtons) 1000 else 300),
        label = "micButtonColor"
    )

    // Counts up while recording is active.
    LaunchedEffect(isRecordingActive) {
        if (isRecordingActive) {
            recordingSeconds = 0
            while (true) {
                delay(1000)
                recordingSeconds++
            }
        }
    }

    // Starts recording after a 300 ms hold. Cancelled automatically if the finger
    // is released (isMicrophonePressed → false) before the delay elapses.
    LaunchedEffect(isMicrophonePressed) {
        if (isMicrophonePressed) {
            haptics.perform(HapticEvent.LongPress)
            delay(300)

            if (!recordAudioPermissionState.hasPermission) {
                recordAudioPermissionState.requestPermission()
                return@LaunchedEffect
            }

            isRecordingActive = true
            onRecordingStarted()
            Logger.d("Recording started")
        }
    }

    Column(
        modifier = modifier
    ) {
        AnimatedVisibility(
            visible = !isRecordingActive,
            enter = signalFadeIn,
            exit = signalFadeOut,
        ) {
            Column {
                if (isDesktopOrWeb()) {
                    RichTextEditorButtons(
                        modifier = Modifier.fillMaxWidth(),
                        state = state,
                        enabled = isKeyboardFocused,
                    )
                }
                payloadRenderers.forEach { att ->
                    PayloadRendererRow(
                        attachment = att,
                        onCancel = { onCancelAttachment(att.id) },
                    )
                }
            }
        }
        if (isRecordingActive && showActionButtons) {
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Left area: text field (normal) or recording progress (while recording).
            // These are siblings in a Box so the recording overlay never covers the mic
            // button on the right, which keeps its pointerInput alive throughout the gesture.
            Box(modifier = Modifier.weight(1f)) {
                Column {
                    if (editExistingMode && showActionButtons) {
                        MessageEditMessageInfo(
                            showingEmojiSheet = showingEmojiSheet,
                            showExtraButtons = true,
                            onEmojiClick = onEmojiClick,
                            onKeyboardClick = onKeyboardClick,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val pasteModifier = if (onPasteImage != null) {
                            clipboardImageReceiverModifier { bytes -> onPasteImage.invoke(bytes) }
                        } else {
                            Modifier
                        }
                        val pasteImageLabel = stringResource(MR.string.chat_message_paste_image)
                        RichTextEditor(
                            state = state,
                            modifier = Modifier
                                .weight(1f)
                                .then(pasteModifier)
                                .then(
                                    if (onPasteImage != null)
                                        Modifier.pasteImageContextMenuItem(
                                            label = pasteImageLabel,
                                            enabled = true,
                                        ) {
                                            pasteScope.launch {
                                                readClipboardImage()?.let { onPasteImage.invoke(it) }
                                            }
                                        }
                                    else Modifier
                                )
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    isKeyboardFocused = focusState.isFocused
                                    if (focusState.isFocused) {
                                        onFocused()
                                    }
                                }
                                .onPreviewKeyEvent { keyEvent ->
                                    // Cmd/Ctrl+V image paste works on any platform with a hardware
                                    // keyboard — desktop, web, AND iOS/iPad. Enter-to-send (below)
                                    // stays desktop/web only; mobile uses the send button.
                                    if (keyEvent.type == KeyEventType.KeyDown &&
                                        (isDesktopOrWeb() ||
                                            (keyEvent.key == Key.V && (keyEvent.isCtrlPressed || keyEvent.isMetaPressed)))
                                    ) {
                                        when {
                                            // Shift+Enter inserts a newline; every other
                                            // Enter/NumPadEnter (incl. Cmd/Ctrl+Enter) sends.
                                            // Match NumPadEnter too — macOS can report Return as
                                            // NumPadEnter, so Key.Enter alone never fired (#1043).
                                            (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter) &&
                                                keyEvent.isShiftPressed -> {
                                                state.addTextAfterSelection("\n")
                                                true
                                            }

                                            keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter -> {
                                                onSendMessage()
                                                true
                                            }

                                            keyEvent.key == Key.V && (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) && onPasteImage != null -> {
                                                val imageBytes = getImageFromClipboard()
                                                if (imageBytes != null) {
                                                    onPasteImage.invoke(imageBytes)
                                                    true
                                                } else {
                                                    // The browser clipboard is async-only, so the read above
                                                    // always returns null on web. Start the async read and
                                                    // report the event unhandled: consuming it would swallow
                                                    // an ordinary text paste, and we cannot know yet whether
                                                    // the clipboard holds an image.
                                                    pasteScope.launch {
                                                        readClipboardImage()?.let { onPasteImage.invoke(it) }
                                                    }
                                                    false
                                                }
                                            }

                                            else -> false
                                        }
                                    } else {
                                        false
                                    }
                                },
                            placeholder = { Text(stringResource(MR.string.chat_new_message_placeholder)) },
                            leadingIcon = if (editExistingMode) null else {
                                {
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
                                }
                            },
                            trailingIcon = if (editExistingMode) null else {
                                {
                                    if (state.annotatedString.isNotBlank()) {
                                        IconButton(
                                            onClick = onAddAttachmentClick,
                                            modifier = Modifier.testTag("inline_attach_button"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = stringResource(MR.string.chat_message_attachment_options)
                                            )
                                        }
                                    } else if (isMobile()) {
                                        var showCameraMenu by remember { mutableStateOf(false) }
                                        Box {
                                            IconButton(
                                                onClick = { showCameraMenu = true },
                                                modifier = Modifier.testTag("camera_button"),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PhotoCamera,
                                                    contentDescription = stringResource(MR.string.chat_message_camera)
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = showCameraMenu,
                                                onDismissRequest = { showCameraMenu = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(MR.string.chat_message_take_photo)) },
                                                    onClick = {
                                                        showCameraMenu = false
                                                        onCameraClick()
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(MR.string.chat_message_record_video)) },
                                                    onClick = {
                                                        showCameraMenu = false
                                                        onVideoRecordClick()
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Videocam, contentDescription = null)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            shape = if (!showActionButtons)
                                RoundedCornerShape(0.dp)
                            else if (editExistingMode)
                                RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                            else if (showRecordingButton)
                                RoundedCornerShape(bottomStart = 12.dp, topStart = 12.dp)
                            else
                                RoundedCornerShape(12.dp),
                            colors = RichTextEditorDefaults.richTextEditorColors(
                                containerColor = if (!showActionButtons)
                                    Color.Transparent
                                else
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            minLines = 1,
                            maxLines = if (editExistingMode) 10 else 5,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Default
                            )
                        )
                        if (showRecordingButton) {
                            // Mic button: a single pointerInput handles the full gesture (press,
                            // hold to record, slide-left to cancel, release to stop). It is always
                            // outside the recording overlay so Compose never cancels the pointer.
                            Box(
                                modifier = Modifier
                                    .size(micButtonSize)
                                    .testTag("mic_button")
                                    .clip(
                                        if (isMicrophonePressed || !showActionButtons) CircleShape
                                        else RoundedCornerShape(
                                            bottomEnd = 12.dp,
                                            topEnd = 12.dp
                                        )
                                    )
                                    .background(if (!showActionButtons) Color.Transparent else micButtonColor)
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown()
                                            down.consume()
                                            isMicrophonePressed = true

                                            // Track pointer until released.
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change =
                                                    event.changes.firstOrNull { it.id == down.id }
                                                        ?: break
                                                if (!change.pressed) {
                                                    change.consume()
                                                    break
                                                }
                                                // Update drag offset (finger moving left = negative X delta).
                                                if (isRecordingActive) {
                                                    dragOffset =
                                                        (change.position.x - down.position.x)
                                                            .coerceAtMost(0f)
                                                    if (dragOffset < -cancelThresholdPx) {
                                                        // Threshold crossed – cancel and drain until release.
                                                        isMicrophonePressed = false
                                                        isRecordingActive = false
                                                        dragOffset = 0f
                                                        Logger.d("Recording cancelled")
                                                        onRecordingCancelled()
                                                        while (true) {
                                                            val ev = awaitPointerEvent()
                                                            val ch =
                                                                ev.changes.firstOrNull { it.id == down.id }
                                                                    ?: break
                                                            ch.consume()
                                                            if (!ch.pressed) break
                                                        }
                                                        return@awaitEachGesture
                                                    }
                                                }
                                                change.consume()
                                            }

                                            // Finger released normally.
                                            val wasRecording = isRecordingActive
                                            isMicrophonePressed = false
                                            isRecordingActive = false
                                            dragOffset = 0f

                                            if (!wasRecording) {
                                                Logger.d("Recording help (quick tap)")
                                                onRecordingHelp()
                                            } else {
                                                Logger.d("Recording ended")
                                                onRecordingStopped()
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = stringResource(MR.string.chat_message_microphone),
                                    tint = if (isMicrophonePressed) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Recording progress overlay – covers only the text field Box so the
                // mic button on the right stays fully accessible to pointer events.
                // Must be instant (no animation): matchParentSize() requires BoxScope,
                // and the overlay must appear immediately to block text field input
                // during the active pointer gesture.
                if (isRecordingActive) {
                    RecordingInProgress(
                        recordingSeconds,
                        dragOffset,
                        cancelThresholdPx,
                        recordingData?.isProcessing ?: false
                    )
                }
            }

            if (showActionButtons) {
                Spacer(modifier = Modifier.width(8.dp))

                if (isRecordingActive) {
                    Spacer(modifier = Modifier.width(56.dp))
                } else {
                    val standaloneFab = when {
                        editExistingMode -> StandaloneFabAction.Confirm
                        showSendButton -> StandaloneFabAction.Send
                        else -> StandaloneFabAction.Attach
                    }
                    Column {
                        AnimatedVisibility(
                            visible = editExistingMode,
                            enter = signalFadeIn,
                            exit = signalFadeOut,
                        ) {
                            Column {
                                IconButton(
                                    onClick = onCancelEdit,
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    modifier = Modifier
                                        .padding(bottom = 4.dp)
                                        .testTag("cancel_fab")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(MR.string.cancel),
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                        val fabClick = if (standaloneFab == StandaloneFabAction.Attach)
                            onAddAttachmentClick else onSendMessage
                        val fabEnabled = when (standaloneFab) {
                            StandaloneFabAction.Attach -> true
                            else -> !isSendingMessage
                        }
                        val fabTestTag = when (standaloneFab) {
                            StandaloneFabAction.Confirm -> "confirm_fab"
                            StandaloneFabAction.Send -> "send_fab"
                            StandaloneFabAction.Attach -> "attachment_fab"
                        }
                        IconButton(
                            onClick = fabClick,
                            enabled = fabEnabled,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                                contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                            ),
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .testTag(fabTestTag),
                        ) {
                            AnimatedContent(
                                targetState = standaloneFab,
                                transitionSpec = { signalToggleIn togetherWith signalToggleOut },
                                label = "standalone_fab_icon_toggle",
                            ) { action ->
                                Icon(
                                    imageVector = when (action) {
                                        StandaloneFabAction.Confirm -> Icons.Filled.Check
                                        StandaloneFabAction.Send -> Icons.AutoMirrored.Filled.Send
                                        StandaloneFabAction.Attach -> Icons.Default.Add
                                    },
                                    contentDescription = when (action) {
                                        StandaloneFabAction.Attach -> stringResource(MR.string.chat_message_attachment_options)
                                        else -> stringResource(MR.string.chat_send_message_button)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.RecordingInProgress(
    recordingSeconds: Int,
    dragOffset: Float,
    cancelThresholdPx: Float,
    isProcessing: Boolean,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dot"
    )

    val minutes = recordingSeconds / 60
    val secs = recordingSeconds % 60
    val timeText = "${minutes.toString().padStart(2, '0')}:${
        secs.toString().padStart(2, '0')
    }"
    Row(
        modifier = Modifier
            .matchParentSize()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier
                .size(24.dp)
                .alpha(dotAlpha),
            imageVector = Icons.Default.Mic,
            contentDescription = stringResource(MR.string.chat_message_microphone),
            tint = Color.Red,
        )
        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = timeText,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (isProcessing) {
            Text(
                text = stringResource(MR.string.chat_message_processing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(MR.string.slide_to_cancel),
                modifier = Modifier.offset {
                    IntOffset((dragOffset / 2).roundToInt(), 0)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = (1f + dragOffset / cancelThresholdPx).coerceIn(0f, 1f)
                ),
            )
        }
    }
}

@Composable
fun BlueBackgroundIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String?,
    enabled: Boolean = true,
    testTag: String = "",
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
            contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
        ),
        modifier = modifier
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier)
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription
        )
    }
}

@Composable
private fun MessageEditMessageInfo(
    modifier: Modifier = Modifier,
    showExtraButtons: Boolean = false,
    showingEmojiSheet: Boolean,
    onEmojiClick: () -> Unit,
    onKeyboardClick: () -> Unit,
) {
    Row(
        modifier = modifier
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
            modifier = Modifier.padding(8.dp).weight(1f),
            style = MaterialTheme.typography.labelSmall,
        )
        if (showExtraButtons) {
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageTextFieldForAttachment(
    modifier: Modifier = Modifier,
    state: RichTextState,
    onSmileyClick: () -> Unit,
    onSendMessage: () -> Unit,
    // Mirror the chat composer: the rich-text formatting toolbar is desktop/web-only.
    // On mobile (Android/iOS) the caption editor hides it. Injectable so both branches
    // are unit-testable without a device.
    showFormattingToolbar: Boolean = isDesktopOrWeb(),
) {
    var hasSent by remember { mutableStateOf(false) }
    val isKeyboardVisible by keyboardAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = modifier) {
        if (showFormattingToolbar) {
            RichTextEditorButtons(
                modifier = Modifier.fillMaxWidth().testTag("attachment_formatting_toolbar"),
                state = state,
                enabled = true,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RichTextEditor(
                state = state,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { keyEvent ->
                    if (isDesktopOrWeb() && keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                        if (keyEvent.isShiftPressed) {
                            state.addTextAfterSelection("\n")
                            true
                        } else if (!hasSent) {
                            hasSent = true
                            onSendMessage()
                            true
                        } else {
                            true
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
                    onClick = { hasSent = true; onSendMessage() },
                    enabled = !hasSent,
                    colors = IconButtonDefaults.iconButtonColors(
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

@OptIn(ExperimentalRichTextApi::class)
@Composable
fun RichTextEditorButtons(
    modifier: Modifier = Modifier,
    state: RichTextState,
    enabled: Boolean,
) {
    // Localized a11y labels for the new authoring affordances. Resolved here
    // (outside the LazyRow item lambdas) so the Konsist no-literal-Text gate is
    // satisfied and the labels survive into TalkBack/VoiceOver.
    val headingLabel = stringResource(MR.string.chat_markdown_heading)
    val blockquoteLabel = stringResource(MR.string.chat_markdown_blockquote)
    val inlineCodeLabel = stringResource(MR.string.chat_markdown_inline_code)
    val codeBlockLabel = stringResource(MR.string.chat_markdown_code_block)
    val linkLabel = stringResource(MR.string.chat_markdown_link)
    val nestedListLabel = stringResource(MR.string.chat_markdown_nested_list)

    var showLinkDialog by remember { mutableStateOf(false) }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    LazyRow(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        item {
            RichTextStyleButton(
                onClick = {
                    state.toggleSpanStyle(
                        SpanStyle(fontWeight = FontWeight.Bold)
                    )
                },
                enabled = enabled,
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
                enabled = enabled,
                isSelected = state.currentSpanStyle.fontStyle == FontStyle.Italic,
                icon = Icons.Outlined.FormatItalic
            )
        }

        // Underline intentionally dropped: it has no CommonMark representation,
        // so toMarkdown() would silently lose it and it would not round-trip to
        // other Homebase clients (which speak markdown on the wire) nor render in
        // the mikepenz CommonMark renderer.

        item {
            RichTextStyleButton(
                onClick = {
                    state.toggleSpanStyle(
                        SpanStyle(
                            textDecoration = TextDecoration.LineThrough
                        )
                    )
                },
                enabled = enabled,
                isSelected = state.currentSpanStyle.textDecoration?.contains(
                    TextDecoration.LineThrough
                ) == true,
                icon = Icons.Outlined.FormatStrikethrough,
            )
        }

        // Inline code (re-enabled). Styled by the renderer as a monospace chip.
        item {
            RichTextStyleButton(
                onClick = { state.toggleCodeSpan() },
                enabled = enabled,
                isSelected = state.isCodeSpan,
                icon = Icons.Outlined.Code,
                contentDescription = inlineCodeLabel,
            )
        }

        // Insert a link via dialog (text + URL).
        item {
            RichTextStyleButton(
                onClick = { showLinkDialog = true },
                enabled = enabled,
                isSelected = state.isLink,
                icon = Icons.Outlined.AddLink,
                contentDescription = linkLabel,
            )
        }

        item {
            Box(
                Modifier.height(24.dp).width(1.dp).background(dividerColor)
            )
        }

        // Heading: cycle Normal -> H1 -> H2 -> Normal (richeditor serialises the
        // level as a CommonMark ATX prefix `# ` / `## `).
        item {
            RichTextStyleButton(
                onClick = {
                    val next = when (state.currentHeadingStyle) {
                        HeadingStyle.Normal -> HeadingStyle.H1
                        HeadingStyle.H1 -> HeadingStyle.H2
                        else -> HeadingStyle.Normal
                    }
                    state.setHeadingStyle(next)
                },
                enabled = enabled,
                isSelected = state.currentHeadingStyle != HeadingStyle.Normal,
                icon = Icons.Outlined.Title,
                contentDescription = headingLabel,
            )
        }

        // Blockquote and fenced code block. richeditor has no paragraph-level
        // toggle for these, so we insert the CommonMark markers at the cursor;
        // the editor renders them live and toMarkdown() emits them verbatim.
        item {
            RichTextStyleButton(
                onClick = { state.addTextAfterSelection("\n> ") },
                enabled = enabled,
                icon = Icons.Outlined.FormatQuote,
                contentDescription = blockquoteLabel,
            )
        }

        item {
            RichTextStyleButton(
                onClick = { state.addTextAfterSelection("\n```\n\n```\n") },
                enabled = enabled,
                icon = Icons.Outlined.Terminal,
                contentDescription = codeBlockLabel,
            )
        }

        item {
            Box(
                Modifier.height(24.dp).width(1.dp).background(dividerColor)
            )
        }

        item {
            RichTextStyleButton(
                onClick = { state.toggleUnorderedList() },
                isSelected = state.isUnorderedList,
                enabled = enabled,
                icon = Icons.AutoMirrored.Outlined.FormatListBulleted,
            )
        }

        item {
            RichTextStyleButton(
                onClick = { state.toggleOrderedList() },
                isSelected = state.isOrderedList,
                enabled = enabled,
                icon = Icons.Outlined.FormatListNumbered,
            )
        }

        // Nested list: increase the indent level of the current list item. Only
        // meaningful while the cursor sits inside a list.
        item {
            RichTextStyleButton(
                onClick = { state.increaseListLevel() },
                enabled = enabled && state.isList,
                icon = Icons.AutoMirrored.Outlined.FormatIndentIncrease,
                contentDescription = nestedListLabel,
            )
        }
    }

    if (showLinkDialog) {
        MarkdownLinkDialog(
            initialText = "",
            onDismiss = { showLinkDialog = false },
            onConfirm = { text, url ->
                if (text.isNotBlank() && url.isNotBlank()) {
                    state.addLink(text = text, url = url)
                }
                showLinkDialog = false
            },
        )
    }
}

/**
 * Small dialog to insert a markdown link as `[text](url)`. The editor's
 * [RichTextState.addLink] handles the actual insertion; this only collects the
 * two fields. All labels come from string resources so the Konsist no-literal
 * gate is satisfied and the dialog is localizable.
 */
@Composable
private fun MarkdownLinkDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (text: String, url: String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.chat_markdown_link_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(MR.string.chat_markdown_link_dialog_text)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(MR.string.chat_markdown_link_dialog_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text, url) }) {
                Text(stringResource(MR.string.chat_markdown_link_dialog_insert))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.string.chat_markdown_link_dialog_cancel))
            }
        },
    )
}

