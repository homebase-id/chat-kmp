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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import id.homebase.api.common.OdinId
import id.homebase.api.util.markdownToPlainPreview
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.services.convo.OneOnOneConnectionStatus
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.ifTrue
import id.homebase.resources.MR
import id.homebase.resources.chat_archived
import id.homebase.resources.chat_connection_invitation_received
import id.homebase.resources.chat_connection_invitation_sent
import id.homebase.resources.chat_connection_invited
import id.homebase.resources.chat_connection_not_connected
import id.homebase.resources.chat_connection_wants_to_connect
import id.homebase.resources.chat_group_legacy
import id.homebase.resources.chat_group_rejoin_pending
import id.homebase.resources.chat_no_messages
import id.homebase.resources.chat_note_to_self
import id.homebase.resources.chat_search_result_pinned
import id.homebase.resources.you
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
                    text = if (enrichedData.conversation.isWithSelf)
                        stringResource(MR.string.chat_note_to_self)
                    else enrichedData.getDisplayName(youLabel = stringResource(MR.string.you)),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (enrichedData.conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (enrichedData.conversation.isPinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = stringResource(MR.string.chat_search_result_pinned),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

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

                // When there's no history for a 1:1 conversation with a pending connection
                // request, swap the default "No messages yet" fallback for a line that
                // actually tells the user what's happening.
                val pendingSubtitle: String? = if (
                    contentLabel == null &&
                    enrichedData.conversation.lastMessage.isBlank()
                ) {
                    when (enrichedData.oneOnOneConnectionStatus) {
                        is OneOnOneConnectionStatus.OutgoingRequestPending ->
                            stringResource(MR.string.chat_connection_invitation_sent)
                        is OneOnOneConnectionStatus.IncomingRequestPending ->
                            stringResource(MR.string.chat_connection_invitation_received)
                        is OneOnOneConnectionStatus.NotConnected ->
                            stringResource(MR.string.chat_connection_not_connected)
                        else -> null
                    }
                } else null

                val previewText = pendingSubtitle
                    ?: contentLabel?.text
                    ?: enrichedData.conversation.lastMessage
                val iconRes = if (pendingSubtitle != null) null else contentLabel?.icon

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
                when (enrichedData.oneOnOneConnectionStatus) {
                    is OneOnOneConnectionStatus.OutgoingRequestPending -> {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(MR.string.chat_connection_invited),
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    is OneOnOneConnectionStatus.IncomingRequestPending -> {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(MR.string.chat_connection_wants_to_connect),
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    is OneOnOneConnectionStatus.NotConnected -> {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(MR.string.chat_connection_not_connected),
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.errorContainer,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    else -> {}
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
            // A conversation-list row is a single line, so render the stripped
            // plain-text preview rather than the block markdown renderer — a
            // heading/list/quote would otherwise blow up the one-liner. The
            // strip is the same AST walk the renderer and previews share, and it
            // sidesteps the old RichTextState infinite-recomposition footgun
            // entirely (no Compose MutableState is mutated during composition).
            val previewText = remember(text) {
                markdownToPlainPreview(text, maxCodePoints = 200)
            }

            Text(
                text = previewText,
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
