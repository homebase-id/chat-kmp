package id.homebase.chat.contactcard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_contact_card_actions
import id.homebase.resources.chat_contact_card_more
import id.homebase.resources.chat_contact_card_open
import id.homebase.resources.chat_contact_card_save
import id.homebase.resources.chat_contact_card_title
import id.homebase.resources.chat_contact_unparseable
import id.homebase.resources.contactbook_edit_email
import id.homebase.resources.contactbook_edit_phone
import org.jetbrains.compose.resources.stringResource

/**
 * In-stream bubble for a shared [ContactCardDescriptor]. Tap opens [ContactCardDetailDialog], which
 * owns every phone/email plus the per-value actions; the bubble shows at most
 * [ContactCardBubbleRowLimit] values so a 10-phone card can't grow unbounded.
 *
 * @param canOpenDetail false off-stream (action-menu preview, message info, reply quote), where the
 *   card is a picture of a message rather than the message, and must not be tappable.
 * @param footer send time + delivery status; supplied by the host, which owns the message metadata.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactCardBubble(
    descriptor: ContactCardDescriptor?,
    modifier: Modifier = Modifier,
    onSaveToContacts: ((ContactCardDescriptor) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    canOpenDetail: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    if (descriptor == null || !descriptor.isValid()) {
        UnparseableContactCardBubble(modifier = modifier, footer = footer)
        return
    }

    var showDetail by remember(descriptor) { mutableStateOf(false) }
    val preview = remember(descriptor) { descriptor.bubbleValues() }
    val subtitle = remember(descriptor) { descriptor.subtitleLine() }
    val title = descriptor.summaryLine().ifBlank { stringResource(MR.string.chat_contact_card_title) }
    val phoneLabel = stringResource(MR.string.contactbook_edit_phone)
    val emailLabel = stringResource(MR.string.contactbook_edit_email)

    val openLabel = stringResource(MR.string.chat_contact_card_open)
    val actionsLabel = stringResource(MR.string.chat_contact_card_actions)
    Surface(
        modifier = modifier.widthIn(min = 240.dp, max = 320.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                // One node for the card, not twelve siblings; Save below stays its own target.
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .let {
                        // Off-stream the card must not be tappable: the detail dialog would draw
                        // over the action menu that drew this preview.
                        if (canOpenDetail) {
                            it.combinedClickable(
                                onClick = { showDetail = true },
                                onClickLabel = openLabel,
                                onLongClick = onLongClick,
                                // Long-press is the card's only route to the action menu.
                                onLongClickLabel = actionsLabel,
                            )
                        } else it
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                ContactCardAvatar(descriptor = descriptor, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    preview.rows.forEach { row ->
                        ValuePreviewRow(
                            value = row,
                            phoneLabel = phoneLabel,
                            emailLabel = emailLabel,
                        )
                    }
                    if (preview.hiddenCount > 0) {
                        Text(
                            text = stringResource(
                                MR.string.chat_contact_card_more,
                                preview.hiddenCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (onSaveToContacts != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(
                    onClick = { onSaveToContacts(descriptor) },
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(MR.string.chat_contact_card_save),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            footer?.invoke(this)
        }
    }

    if (showDetail) {
        ContactCardDetailDialog(
            descriptor = descriptor,
            onDismiss = { showDetail = false },
            // Close first: the editor is hosted above the nav graph and this dialog would outlive it.
            onSaveToContacts = onSaveToContacts?.let { save ->
                { card ->
                    showDetail = false
                    save(card)
                }
            },
        )
    }
}

@Composable
internal fun ContactCardAvatar(
    descriptor: ContactCardDescriptor,
    size: Dp,
) {
    val initials = remember(descriptor) { descriptor.avatarInitials() }
    // Holds sp text, so a fixed dp clips it at a large font scale; capped so 2x doesn't eat the row.
    val diameter = size * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    Box(
        // Decoration: the initials only re-render the name that follows.
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (initials.isBlank()) {
            Icon(
                imageVector = Icons.Outlined.ContactPage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(diameter / 2),
            )
        } else {
            Text(
                text = initials,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ValuePreviewRow(
    value: ContactCardValue,
    phoneLabel: String,
    emailLabel: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = value.kind.icon(),
            contentDescription = if (value.kind == ContactValueKind.Phone) phoneLabel else emailLabel,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value.value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun ContactValueKind.icon(): ImageVector = when (this) {
    ContactValueKind.Phone -> Icons.Outlined.Phone
    ContactValueKind.Email -> Icons.Outlined.AlternateEmail
}

@Composable
private fun UnparseableContactCardBubble(
    modifier: Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContactPage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(MR.string.chat_contact_unparseable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            footer?.invoke(this)
        }
    }
}
