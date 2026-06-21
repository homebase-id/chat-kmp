package id.homebase.core.ui.screens.feed.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.conversationlist.materializeForUpload
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.toImageAttachmentInput
import id.homebase.core.widget.EmojiSelectorDialog
import id.homebase.resources.MR
import id.homebase.resources.feed_comment_attach_image
import id.homebase.resources.feed_comment_attachment_label
import id.homebase.resources.feed_comment_emoji
import id.homebase.resources.feed_comment_hint
import id.homebase.resources.feed_comment_remove_attachment
import id.homebase.resources.feed_comment_replying_to
import id.homebase.resources.feed_comment_send
import id.homebase.resources.feed_reply_cancel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Bottom comment composer: a text field with an emoji button, a single-image
 * attach affordance, and a send button. When [replyingToName] is non-null a
 * dismissible "Replying to …" banner sits above the field.
 *
 * The picked [PlatformFile] is resolved to an [AttachmentInput] at send time via
 * the shared cross-platform [toImageAttachmentInput] helper (the same path chat's
 * own composers use), so the widget stays free of any feed service/ViewModel.
 *
 * @param onSend fired with the trimmed text and the resolved attachment (or null).
 * @param replyingToName name of the comment being replied to, if any.
 * @param onCancelReply clears the active reply target.
 */
@Composable
fun CommentComposer(
    onSend: (text: String, attachment: AttachmentInput?) -> Unit,
    modifier: Modifier = Modifier,
    replyingToName: String? = null,
    onCancelReply: () -> Unit = {},
) {
    val fileOps: FileOperationsProvider = koinInject()
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var pickedImage by remember { mutableStateOf<PlatformFile?>(null) }
    var showEmojiPicker by remember { mutableStateOf(false) }

    val imagePicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file != null) pickedImage = file
    }

    val canSend = text.isNotBlank() || pickedImage != null

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
                IconButton(onClick = onCancelReply, modifier = Modifier.size(28.dp)) {
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
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                leadingIcon = {
                    IconButton(onClick = { showEmojiPicker = true }) {
                        Icon(
                            imageVector = Icons.Default.EmojiEmotions,
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
                    onClick = {
                        val body = text.trim()
                        val image = pickedImage
                        scope.launch {
                            val attachment = image?.let {
                                it.materializeForUpload(fileOps).toImageAttachmentInput(fileOps)
                            }
                            onSend(body, attachment)
                            text = ""
                            pickedImage = null
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
    }

    if (showEmojiPicker) {
        EmojiSelectorDialog(
            onDismiss = { showEmojiPicker = false },
            onEmojiSelected = { emoji ->
                showEmojiPicker = false
                text += emoji
            },
        )
    }
}
