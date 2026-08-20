package id.homebase.core.ui.screens.feed.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.conversationlist.materializeForUpload
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.toImageAttachmentInput
import id.homebase.chat.services.sticker.SavedSticker
import id.homebase.chat.services.sticker.StickerService
import id.homebase.chat.services.sticker.StickerStream
import id.homebase.chat.widget.StickerTray
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.StickerFilled
import id.homebase.core.ui.assets.StickerOutlined
import id.homebase.core.widget.EmojiSelection
import id.homebase.resources.MR
import id.homebase.resources.feed_comment_attach_image
import id.homebase.resources.feed_comment_attachment_label
import id.homebase.resources.feed_comment_emoji
import id.homebase.resources.feed_comment_hint
import id.homebase.resources.feed_comment_remove_attachment
import id.homebase.resources.feed_comment_replying_to
import id.homebase.resources.feed_comment_send
import id.homebase.resources.feed_comment_sticker
import id.homebase.resources.feed_reply_cancel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

// The picked PlatformFile is resolved to an AttachmentInput at send time via the shared
// [toImageAttachmentInput], so this widget stays free of any feed service/ViewModel.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentComposer(
    onSend: (text: String, attachment: AttachmentInput?) -> Unit,
    modifier: Modifier = Modifier,
    replyingToName: String? = null,
    onCancelReply: () -> Unit = {},
) {
    val fileOps: FileOperationsProvider = koinInject()
    val stickerStream: StickerStream = koinInject()
    val stickerService: StickerService = koinInject()
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var pickedImage by remember { mutableStateOf<PlatformFile?>(null) }
    // One combined expression panel (emoji + stickers), rendered inline in the keyboard area so the input row
    // stays visible while picking.
    var showExpressionSheet by remember { mutableStateOf(false) }
    var expressionTab by remember { mutableStateOf(ExpressionTab.Emoji) }
    // Send is async and text/pickedImage only clear once it finishes, so without this an impatient second tap
    // posts a duplicate — each postComment mints its own uniqueId.
    var sending by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val fieldFocusRequester = remember { FocusRequester() }

    // Keyed on the null-ness so a late display-name resolution doesn't re-fire it; the frame wait covers the
    // iOS first-responder race.
    LaunchedEffect(replyingToName != null) {
        if (replyingToName != null) {
            withFrameNanos {}
            fieldFocusRequester.requestFocus()
            keyboard?.show()
        }
    }

    val stickers by stickerStream.stickers.collectAsStateWithLifecycle()
    val stickersLoaded by stickerStream.isLoaded.collectAsStateWithLifecycle()

    val imagePicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file != null) pickedImage = file
    }

    // resolveForSend downloads + decrypts the sticker to a temp file, which is then re-staged as an image
    // upload — the exact path chat uses. A sticker-only comment (empty text) is valid.
    val sendSticker: (SavedSticker) -> Unit = { sticker ->
        if (!sending) {
            sending = true
            val body = text.trim()
            scope.launch {
                try {
                    val path = stickerService.resolveForSend(sticker) ?: return@launch
                    val attachment = platformFileFromPath(path)
                        .materializeForUpload(fileOps)
                        .toImageAttachmentInput(fileOps)
                    onSend(body, attachment)
                    text = ""
                } finally {
                    sending = false
                }
            }
        }
    }

    val canSend = (text.isNotBlank() || pickedImage != null) && !sending

    Column(modifier = modifier.fillMaxWidth()) {
        if (replyingToName != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.string.feed_comment_replying_to, replyingToName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Hand focus back to nothing rather than leaving it on a row about to leave the composition.
                IconButton(
                    onClick = {
                        onCancelReply()
                        focusManager.clearFocus()
                        keyboard?.hide()
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(MR.string.feed_reply_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        pickedImage?.let { image ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            MR.string.feed_comment_attachment_label,
                            image.name,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { pickedImage = null },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(
                                MR.string.feed_comment_remove_attachment
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(MR.string.feed_comment_hint)) },
                // Tapping into the field closes the expression panel; the keyboard reclaims the space.
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocusRequester)
                    .onFocusChanged { if (it.isFocused) showExpressionSheet = false },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                leadingIcon = {
                    // Opening the panel drops field focus and hides the keyboard so the panel takes its place.
                    IconButton(onClick = {
                        if (showExpressionSheet) {
                            showExpressionSheet = false
                        } else {
                            keyboard?.hide()
                            focusManager.clearFocus()
                            showExpressionSheet = true
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Mood,
                            contentDescription = stringResource(MR.string.feed_comment_emoji),
                        )
                    }
                },
                trailingIcon = {
                    IconButton(onClick = { imagePicker.launch() }) {
                        Icon(
                            imageVector = Icons.Outlined.AddPhotoAlternate,
                            contentDescription = stringResource(
                                MR.string.feed_comment_attach_image
                            ),
                        )
                    }
                },
            )

            Box {
                IconButton(
                    // `sending` is re-read live here: the `enabled` disable only lands on the next recomposition.
                    onClick = {
                        if (!sending) {
                            sending = true
                            val body = text.trim()
                            val image = pickedImage
                            scope.launch {
                                try {
                                    val attachment = image?.let {
                                        it.materializeForUpload(fileOps)
                                            .toImageAttachmentInput(fileOps)
                                    }
                                    onSend(body, attachment)
                                    text = ""
                                    pickedImage = null
                                } finally {
                                    sending = false
                                }
                            }
                        }
                    },
                    enabled = canSend,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(MR.string.feed_comment_send),
                        tint = if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        AnimatedVisibility(visible = showExpressionSheet) {
            Column(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                ExpressionTabRow(selected = expressionTab, onSelect = { expressionTab = it })
                when (expressionTab) {
                    ExpressionTab.Emoji -> EmojiSelection(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                        messageInputMode = true,
                        onBackSpace = { text = text.dropLast(1) },
                        onEmojiSelected = { emoji -> text += emoji },
                    )
                    // ponytail: long-press (remove) + import are chat-library affordances not relevant when
                    // picking a sticker for a comment; both are hidden.
                    ExpressionTab.Stickers -> StickerTray(
                        stickers = stickers,
                        isLoaded = stickersLoaded,
                        onStickerSelected = { sticker ->
                            showExpressionSheet = false
                            sendSticker(sticker)
                        },
                        onStickerLongPress = {},
                        onImportClick = {},
                        showImportTile = false,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

private enum class ExpressionTab { Emoji, Stickers }

@Composable
private fun ExpressionTabRow(
    selected: ExpressionTab,
    onSelect: (ExpressionTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = { onSelect(ExpressionTab.Emoji) }) {
            Icon(
                imageVector = if (selected == ExpressionTab.Emoji) Icons.Filled.EmojiEmotions
                else Icons.Outlined.EmojiEmotions,
                contentDescription = stringResource(MR.string.feed_comment_emoji),
                tint = if (selected == ExpressionTab.Emoji) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onSelect(ExpressionTab.Stickers) }) {
            Icon(
                imageVector = if (selected == ExpressionTab.Stickers) HomebaseIcons.StickerFilled
                else HomebaseIcons.StickerOutlined,
                contentDescription = stringResource(MR.string.feed_comment_sticker),
                tint = if (selected == ExpressionTab.Stickers) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
