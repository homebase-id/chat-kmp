package id.homebase.chat.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
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
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
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
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.ui.theme.emojiFontFamily
import id.homebase.core.ui.theme.withEmojiFont
import id.homebase.core.util.formatTimestamp
import id.homebase.core.util.ifTrue
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.core.util.isMobile
import id.homebase.core.util.stripComposerLineBreakArtifacts
import id.homebase.resources.MR
import id.homebase.resources.chat_archive
import id.homebase.resources.chat_archived
import id.homebase.resources.chat_connection_invitation_received
import id.homebase.resources.chat_connection_invitation_sent
import id.homebase.resources.chat_connection_invited
import id.homebase.resources.chat_connection_not_connected
import id.homebase.resources.chat_connection_wants_to_connect
import id.homebase.resources.chat_conversation_draft
import id.homebase.resources.chat_group_legacy
import id.homebase.resources.chat_group_rejoin_pending
import id.homebase.resources.chat_mark_all_as_read
import id.homebase.resources.chat_no_messages
import id.homebase.resources.chat_note_to_self
import id.homebase.resources.chat_preview_sender_label
import id.homebase.resources.chat_search_result_pinned
import id.homebase.resources.chat_unarchive
import id.homebase.resources.you
import org.jetbrains.compose.resources.stringResource
import kotlin.math.absoluteValue

// Deliberately hard to reach: an accidental archive costs the user more than a missed swipe.
private const val COMMIT_FRACTION_OF_ROW = 0.4f
private const val MAX_TRAVEL_FRACTION_OF_ROW = 0.55f
private const val FLICK_ESCAPE_VELOCITY_DP_PER_SECOND = 1000f

@Composable
fun ConversationItem(
    enrichedData: EnrichedConversationUiModel,
    onClick: () -> Unit,
    onTogglePinClick: (() -> Unit)? = null,
    onArchiveClick: () -> Unit,
    onMarkAsReadClick: (() -> Unit)? = null,
    onContactClick: (odinId: OdinId) -> Unit,
    isSelected: Boolean,
    allowSwipeActions: Boolean = true,
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }

    // Pointer rows follow Signal Desktop: one step down the type scale, title+preview as one block.
    val compact = isDesktopOrWeb()
    val row = Dimens.ConversationRow.metrics(compact)

    val isArchived = enrichedData.conversation.conversationState == ConversationState.Archived
    val archiveLabel = stringResource(
        if (isArchived) MR.string.chat_unarchive else MR.string.chat_archive
    )
    val markAsReadLabel = stringResource(MR.string.chat_mark_all_as_read)
    // Nothing to mark on an already-read row, so that direction stays inert rather than
    // offering a no-op.
    val markAsRead = onMarkAsReadClick?.takeIf { enrichedData.conversation.unreadCount > 0 }

    // SwipeRevealBox works in physical screen space, so the RTL mirroring happens here.
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val onSwipeRight = if (isRtl) markAsRead else onArchiveClick
    val onSwipeLeft = if (isRtl) onArchiveClick else markAsRead

    SwipeRevealBox(
        onSwipeRight = onSwipeRight,
        onSwipeLeft = onSwipeLeft,
        enabled = allowSwipeActions && isMobile(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = row.listGutter)
            .clip(RoundedCornerShape(Dimens.ConversationRow.cornerRadius)),
        commitThreshold = SwipeDistance.Fraction(COMMIT_FRACTION_OF_ROW),
        maxOffset = SwipeDistance.Fraction(MAX_TRAVEL_FRACTION_OF_ROW),
        escapeVelocityDpPerSecond = FLICK_ESCAPE_VELOCITY_DP_PER_SECOND,
        // Archive/restore drops the row from this list, so it leaves rather than snapping
        // back. Mark-as-read keeps the row, so that direction still springs back.
        slideOutOnSwipeRight = !isRtl,
        slideOutOnSwipeLeft = isRtl,
        reveal = { state ->
            val revealingArchive = (state.offsetPx > 0f) != isRtl
            ConversationSwipeReveal(
                state = state,
                atLeftEdge = state.offsetPx > 0f,
                icon = when {
                    !revealingArchive -> Icons.Default.MarkChatRead
                    isArchived -> Icons.Default.Unarchive
                    else -> Icons.Default.Archive
                },
                label = if (revealingArchive) archiveLabel else markAsReadLabel,
                // `tertiary` is unusable here — the app theme never defines it, so it
                // falls back to Material's baseline purple.
                container = if (revealingArchive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.secondary,
                onContainer = if (revealingArchive) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSecondary,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .ifTrue(isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
                .semantics {
                    selected = isSelected
                    // A screen reader can't perform the swipe.
                    customActions = buildList {
                        add(CustomAccessibilityAction(archiveLabel) { onArchiveClick(); true })
                        markAsRead?.let {
                            add(CustomAccessibilityAction(markAsReadLabel) { it(); true })
                        }
                    }
                }
                .padding(
                    horizontal = row.horizontalPadding,
                    vertical = Dimens.ConversationRow.verticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConversationAvatar(
                avatarModel = enrichedData.conversation.avatarModel,
                modifier = Modifier.padding(row.avatarPadding),
                options = AvatarOptions(onClick = { enrichedData.participants.firstOrNull()?.odinId?.let { onContactClick(it) } })
            )

            Spacer(modifier = Modifier.width(Dimens.ConversationRow.avatarGap))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val title = if (enrichedData.conversation.isWithSelf)
                        stringResource(MR.string.chat_note_to_self)
                    else enrichedData.getDisplayName(youLabel = stringResource(MR.string.you))
                    Text(
                        text = title.withEmojiFont(),
                        style = if (compact) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (enrichedData.conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(Dimens.Spacing.item))

                    if (enrichedData.conversation.isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = stringResource(MR.string.chat_search_result_pinned),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(Dimens.Spacing.label))
                    }

                    Text(
                        text = formatTimestamp(enrichedData.conversation.latestMessageTimestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enrichedData.conversation.unreadCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (enrichedData.conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal
                    )
                }

                Spacer(modifier = Modifier.height(row.titlePreviewGap))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Strip richeditor's `<br>` empty-paragraph artifacts so a legacy `<br>` message
                    // shows its real text in the list preview, not a stray break / blank line (#1104).
                    val lastMessagePreview = remember(enrichedData.conversation.lastMessage) {
                        enrichedData.conversation.lastMessage.stripComposerLineBreakArtifacts()
                    }
                    val contentLabel = messageContentLabel(
                        textContent = lastMessagePreview,
                        isDeleted = enrichedData.conversation.lastMessageIsDeleted,
                        firstPayload = enrichedData.conversation.lastMessageFirstPayload,
                        hasMultiplePayloads = enrichedData.conversation.lastMessageHasMultiplePayloads,
                        messageContent = enrichedData.conversation.lastMessageContent,
                    )

                    // When there's no history for a 1:1 conversation with a pending connection
                    // request, swap the default "No messages yet" fallback for a line that
                    // actually tells the user what's happening.
                    val pendingSubtitle: String? = if (
                        contentLabel == null &&
                        lastMessagePreview.isBlank()
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

                    val rawPreview = pendingSubtitle
                        ?: contentLabel?.text
                        ?: lastMessagePreview
                    val groupSenderName: String? = remember(
                        enrichedData.conversation.isGroupConversation,
                        enrichedData.conversation.lastMessageSender,
                        enrichedData.participants,
                    ) {
                        if (!enrichedData.conversation.isGroupConversation) null
                        else enrichedData.participants
                            .firstOrNull { it.odinId == enrichedData.conversation.lastMessageSender }
                            ?.name?.takeIf { it.isNotBlank() }
                            ?.substringBefore(' ')
                    }
                    val senderLabel: String? = when {
                        pendingSubtitle != null -> null
                        enrichedData.conversation.isWithSelf -> null
                        enrichedData.conversation.lastMessageIsFromActiveUser -> stringResource(MR.string.you)
                        else -> groupSenderName
                    }
                    // Render the sender prefix ("You:" / a group member's name) BEFORE the
                    // content-type icon so it reads "You: <icon> Image", not "<icon> You: Image".
                    val senderPrefix = if (senderLabel != null && rawPreview.isNotBlank()) {
                        stringResource(MR.string.chat_preview_sender_label, senderLabel)
                    } else {
                        null
                    }
                    val iconRes = if (pendingSubtitle != null) null else contentLabel?.icon

                    // A non-blank draft takes over the preview line ("Draft: …", tinted)
                    // so the row advertises unsent text the way every messenger does.
                    val draftRaw = enrichedData.conversation.draft
                    val draftPreview = remember(draftRaw) {
                        draftRaw?.stripComposerLineBreakArtifacts()?.takeIf { it.isNotBlank() }
                    }
                    val draftLabel = stringResource(
                        MR.string.chat_preview_sender_label,
                        stringResource(MR.string.chat_conversation_draft),
                    )

                    ConversationMessagePreview(
                        text = draftPreview ?: rawPreview,
                        prefix = if (draftPreview != null) draftLabel else senderPrefix,
                        prefixColor = if (draftPreview != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        iconRes = if (draftPreview != null) null else iconRes,
                        isDeleted = draftPreview == null && enrichedData.conversation.lastMessageIsDeleted,
                        modifier = Modifier.weight(1f)
                    )

                    if (enrichedData.conversation.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(Dimens.Spacing.item))

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
                    } else if (draftPreview == null && enrichedData.conversation.lastMessageIsFromActiveUser && enrichedData.conversation.lastMessageDeliveryStatus != null) {
                        Spacer(modifier = Modifier.width(Dimens.Spacing.label))
                        DeliveryStatus(
                            isPendingSend = enrichedData.conversation.lastMessageIsPendingSend,
                            deliveryStatus = enrichedData.conversation.lastMessageDeliveryStatus,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (enrichedData.conversation.conversationState == ConversationState.Archived) {
                        Spacer(modifier = Modifier.width(Dimens.Spacing.row))
                        Text(stringResource(MR.string.chat_archived),
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (enrichedData.conversation.conversationState == ConversationState.RejoinPending) {
                        Spacer(modifier = Modifier.width(Dimens.Spacing.row))
                        Text(stringResource(MR.string.chat_group_rejoin_pending),
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    if (enrichedData.conversation.isLegacyGroup) {
                        Spacer(modifier = Modifier.width(Dimens.Spacing.row))
                        Text(stringResource(MR.string.chat_group_legacy),
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    when (enrichedData.oneOnOneConnectionStatus) {
                        is OneOnOneConnectionStatus.OutgoingRequestPending -> {
                            Spacer(modifier = Modifier.width(Dimens.Spacing.row))
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
                            Spacer(modifier = Modifier.width(Dimens.Spacing.row))
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
                            Spacer(modifier = Modifier.width(Dimens.Spacing.row))
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
                        isArchived = isArchived,
                        onMarkAsRead = onMarkAsReadClick,
                        onTogglePin = onTogglePinClick,
                        onArchive = onArchiveClick,
                    )
                }
            }
        }
    }
}

/**
 * The strip uncovered behind a swiped conversation row. It spans only the exposed gap, so a
 * row at rest is drawn exactly as before and no opaque row background is needed.
 */
@Composable
private fun BoxScope.ConversationSwipeReveal(
    state: SwipeRevealState,
    atLeftEdge: Boolean,
    icon: ImageVector,
    label: String,
    container: Color,
    onContainer: Color,
) {
    val committed = state.isPastThreshold
    val background by animateColorAsState(
        targetValue = if (committed) container else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(durationMillis = 150),
    )
    val tint by animateColorAsState(
        targetValue = if (committed) onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 150),
    )
    val pop by animateFloatAsState(
        targetValue = if (committed) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
    )
    val revealWidth = with(LocalDensity.current) { state.offsetPx.absoluteValue.toDp() }

    Box(modifier = Modifier.matchParentSize()) {
        Box(
            modifier = Modifier
                .align(if (atLeftEdge) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
                .fillMaxHeight()
                .width(revealWidth)
                .background(background),
            contentAlignment = if (atLeftEdge) AbsoluteAlignment.CenterLeft
            else AbsoluteAlignment.CenterRight,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .absolutePadding(
                        left = if (atLeftEdge) 20.dp else 0.dp,
                        right = if (atLeftEdge) 0.dp else 20.dp,
                    )
                    .scale((0.6f + 0.4f * state.progress) * pop)
                    .size(24.dp),
            )
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
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = Dimens.Spacing.gutter, vertical = Dimens.Spacing.label)
            .clip(RoundedCornerShape(Dimens.ConversationRow.cornerRadius)).background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(
                    alpha = 0.7f
                )
                else MaterialTheme.colorScheme.surfaceContainerLow
            ).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        ConversationAvatar(
            avatarModel = conversation.avatarModel, modifier = Modifier.padding(Dimens.Spacing.item)
        )
    }
}

@Composable
fun ConversationMessagePreview(
    text: String,
    iconRes: ImageVector?,
    isDeleted: Boolean,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    prefixColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val compact = isDesktopOrWeb()
    val previewStyle = if (compact) MaterialTheme.typography.bodySmall
    else MaterialTheme.typography.bodyMedium
    val emojiFamily = emojiFontFamily()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing.label),
        modifier = modifier
    ) {
        if (prefix != null) {
            Text(
                text = prefix.withEmojiFont(emojiFamily),
                style = previewStyle,
                color = prefixColor.copy(
                    alpha = if (isDeleted) 0.5f else 1f
                ),
                maxLines = 1,
            )
        }

        if (iconRes != null) {
            Icon(
                imageVector = iconRes,
                contentDescription = null,
                modifier = Modifier.size(Dimens.ConversationRow.metrics(compact).previewIconSize)
                    .alpha(if (isDeleted) 0.5f else 1f),
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
            val previewText = remember(text, emojiFamily) {
                markdownToPlainPreview(text, maxCodePoints = 200).withEmojiFont(emojiFamily)
            }

            Text(
                text = previewText,
                style = previewStyle,
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
                style = previewStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("no_messages_text"),
            )
        }
    }
}
