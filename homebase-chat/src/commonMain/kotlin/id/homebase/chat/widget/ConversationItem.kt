package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.ifTrue
import id.homebase.resources.MR
import id.homebase.resources.chat_archived
import id.homebase.resources.chat_group_legacy
import id.homebase.resources.chat_group_rejoin_pending
import id.homebase.resources.chat_no_messages
import id.homebase.resources.chat_note_to_self
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConversationItem(
    enrichedData: EnrichedConversationUiModel,
    onClick: () -> Unit,
    onTogglePinClick: (() -> Unit)? = null,
    onArchiveClick: () -> Unit,
    onMarkAsReadClick: (() -> Unit)? = null,
    onContactClick: (odinId: OdinId) -> Unit,
    isSelected: Boolean,
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .ifTrue(isSelected) {
                Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConversationAvatar(
            avatarModel = enrichedData.conversation.avatarModel,
            modifier = Modifier.padding(8.dp),
            options = AvatarOptions(onClick = { enrichedData.participants.firstOrNull()?.odinId?.let { onContactClick(it) } })
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
                    // LEGACY NOTE TO SELF — simplify to just isNoteToSelf once legacy is removed
                    text = when {
                        enrichedData.conversation.isLegacyNoteToSelf ->
                            stringResource(MR.string.chat_note_to_self) + " (Legacy)"
                        enrichedData.conversation.isAnySelfConversation ->
                            stringResource(MR.string.chat_note_to_self)
                        else -> enrichedData.getDisplayName()
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (enrichedData.conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = formatTimestamp(enrichedData.conversation.latestMessageTimestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enrichedData.conversation.unreadCount > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (enrichedData.conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val contentLabel = messageContentLabel(
                    textContent = enrichedData.conversation.lastMessage,
                    isDeleted = enrichedData.conversation.lastMessageIsDeleted,
                    firstPayload = enrichedData.conversation.lastMessageFirstPayload,
                    hasMultiplePayloads = enrichedData.conversation.lastMessageHasMultiplePayloads,
                )
                val previewText = contentLabel?.text ?: enrichedData.conversation.lastMessage
                val iconRes = contentLabel?.icon

                ConversationMessagePreview(
                    text = previewText,
                    iconRes = iconRes,
                    isDeleted = enrichedData.conversation.lastMessageIsDeleted,
                    modifier = Modifier.weight(1f)
                )

                if (enrichedData.conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))

                    Badge(
                        containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                        contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                    ) {
                        Text(
                            modifier = Modifier.padding(4.dp),
                            text = enrichedData.conversation.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (enrichedData.conversation.lastMessageIsFromActiveUser && enrichedData.conversation.lastMessageDeliveryStatus != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    DeliveryStatus(
                        isPendingSend = false,
                        deliveryStatus = enrichedData.conversation.lastMessageDeliveryStatus,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (enrichedData.conversation.conversationState == ConversationState.Archived) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(MR.string.chat_archived),
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (enrichedData.conversation.conversationState == ConversationState.RejoinPending) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(MR.string.chat_group_rejoin_pending),
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                if (enrichedData.conversation.isLegacyGroup) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(MR.string.chat_group_legacy),
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showMenu) {
                ConversationItemMenuPopup(
                    dismissMenu = { showMenu = false},
                    isPinned = enrichedData.conversation.isPinned,
                    isArchived = enrichedData.conversation.conversationState == ConversationState.Archived,
                    onMarkAsRead = onMarkAsReadClick,
                    onTogglePin = onTogglePinClick,
                    onArchive = onArchiveClick,
                )
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

@Composable
fun ConversationMessagePreview(
    text: String,
    iconRes: ImageVector?,
    isDeleted: Boolean,
    modifier: Modifier = Modifier,
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
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("no_messages_text"),
            )
        }
    }
}
