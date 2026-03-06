package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.formatTimestamp
import id.homebase.resources.MR
import id.homebase.resources.chat_message_audio
import id.homebase.resources.chat_message_deleted
import id.homebase.resources.chat_message_file
import id.homebase.resources.chat_message_image
import id.homebase.resources.chat_message_link
import id.homebase.resources.chat_message_multiple_media
import id.homebase.resources.chat_message_video
import id.homebase.resources.chat_no_messages
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConversationMessagePreview(
    text: String, iconRes: ImageVector?, isDeleted: Boolean, modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        if (iconRes != null) {
            Icon(
                imageVector = iconRes,
                contentDescription = null,
                modifier = Modifier.size(16.dp).alpha(if (isDeleted) 0.5f else 1f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (text.isNotEmpty()) {
            val textState = RichTextState().applyDefaultStyling()
            textState.setMarkdown(text)

            RichText(
                state = textState,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (isDeleted) 0.5f else 1f
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        } else if (iconRes == null) {
            // Fallback for empty message with no icon
            Text(
                text = stringResource(MR.string.chat_no_messages),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ConversationItem(
    groupName: String,
    message: String,
    unreadCount: Int,
    avatarUrl: String,
    avatarInitials: String,
    avatarTiny: EmbeddedThumb?,
    isGroup: Boolean,
    contactOdinId: OdinId?,
    timestamp: Instant,
    onClick: () -> Unit,
    onContactClick: (odinId: OdinId) -> Unit,
    isSelected: Boolean = false,
    avatarModel: ConversationAvatarModel,
    deliveryStatus: Int? = null,
    isDeleted: Boolean = false,
    firstPayload: PayloadDescriptor? = null,
    hasMultiplePayloads: Boolean = false,
    isFromActiveUser: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp)).background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(
                    alpha = 0.7f
                )
                else MaterialTheme.colorScheme.surfaceContainerLow
            ).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConversationAvatar(
            avatarModel = avatarModel,
            modifier = Modifier.padding(8.dp),
            options = AvatarOptions(onClick = { contactOdinId?.let { onContactClick(it) } })
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Content
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = formatTimestamp(timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unreadCount > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val previewText: String
                val iconRes: ImageVector?

                if (isDeleted) {
                    previewText = stringResource(MR.string.chat_message_deleted)
                    iconRes = Icons.Default.Block
                } else if (message.isNotBlank()) {
                    previewText = message
                    iconRes = null
                } else if (hasMultiplePayloads) {
                    previewText = stringResource(MR.string.chat_message_multiple_media)
                    iconRes = Icons.Default.PhotoLibrary
                } else if (firstPayload != null) {
                    when {
                        firstPayload.contentType?.startsWith("image") == true -> {
                            previewText = stringResource(MR.string.chat_message_image)
                            iconRes = Icons.Default.Image
                        }

                        firstPayload.contentType?.startsWith("video") == true || firstPayload.contentType == "application/vnd.apple.mpegurl" -> {
                            previewText = stringResource(MR.string.chat_message_video)
                            iconRes = Icons.Default.PlayArrow
                        }

                        firstPayload.contentType?.startsWith("audio") == true -> {
                            previewText = stringResource(MR.string.chat_message_audio)
                            iconRes = Icons.Default.PlayArrow
                        }

                        firstPayload.key == ChatProtocol.PAYLOAD_KEY_LINKS -> {
                            previewText = stringResource(MR.string.chat_message_link)
                            iconRes = Icons.Default.Description
                        }

                        // Assume link identification or default fallback
                        else -> {
                            previewText = stringResource(MR.string.chat_message_file)
                            iconRes = Icons.Default.Description
                        }
                    }
                } else {
                    previewText = ""
                    iconRes = null
                }

                ConversationMessagePreview(
                    text = previewText,
                    iconRes = iconRes,
                    isDeleted = isDeleted,
                    modifier = Modifier.weight(1f)
                )

                if (unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))

                    Badge(
                        containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                        contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                    ) {
                        Text(
                            modifier = Modifier.padding(4.dp),
                            text = unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (isFromActiveUser && deliveryStatus != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    DeliveryStatus(deliveryStatus = deliveryStatus)
                }
            }
        }
    }
}

@Composable
fun ConversationAvatarItem(
    onClick: () -> Unit,
    isSelected: Boolean = false,
    conversation: ConversationUiModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp)).background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(
                    alpha = 0.7f
                )
                else MaterialTheme.colorScheme.surfaceContainerLow
            ).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        ConversationAvatar(
            avatarModel = conversation.avatarModel, modifier = Modifier.padding(8.dp)
        )
    }
}
