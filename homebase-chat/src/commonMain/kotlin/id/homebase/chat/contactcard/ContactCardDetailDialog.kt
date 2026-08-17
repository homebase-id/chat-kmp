package id.homebase.chat.contactcard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.resources.MR
import id.homebase.resources.chat_contact_card_action_unavailable
import id.homebase.resources.chat_contact_card_call
import id.homebase.resources.chat_contact_card_close
import id.homebase.resources.chat_contact_card_copied
import id.homebase.resources.chat_contact_card_copy_email
import id.homebase.resources.chat_contact_card_copy_phone
import id.homebase.resources.chat_contact_card_detail_pane
import id.homebase.resources.chat_contact_card_emails
import id.homebase.resources.chat_contact_card_message
import id.homebase.resources.chat_contact_card_phones
import id.homebase.resources.chat_contact_card_save
import id.homebase.resources.chat_contact_card_send_email
import id.homebase.resources.chat_contact_card_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen detail for a received contact card: every phone and email the bubble had to leave
 * out, each with its actions and its own copy button. Call, Message and Send email are offered on
 * every platform — a desktop that has no handler for the scheme throws out of openUri and says so,
 * which is the same contract mailto: already had.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactCardDetailDialog(
    descriptor: ContactCardDescriptor,
    onDismiss: () -> Unit,
    onSaveToContacts: ((ContactCardDescriptor) -> Unit)? = null,
    authorOdinId: String? = null,
    sentByYou: Boolean = false,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ContactCardDetailContent(
            descriptor = descriptor,
            onDismiss = onDismiss,
            onSaveToContacts = onSaveToContacts,
            authorOdinId = authorOdinId,
            sentByYou = sentByYou,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactCardDetailContent(
    descriptor: ContactCardDescriptor,
    onDismiss: () -> Unit,
    onSaveToContacts: ((ContactCardDescriptor) -> Unit)?,
    authorOdinId: String?,
    sentByYou: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val subtitle = remember(descriptor) { descriptor.subtitleLine() }

    val copiedMessage = stringResource(MR.string.chat_contact_card_copied)
    val copyValue: (String) -> Unit = { value ->
        scope.launch {
            clipboard.setClipEntry(clipEntryOf(value))
            snackbarHostState.showSnackbar(copiedMessage)
        }
    }
    // A device with no dialer or mail client throws out of openUri; a silent tap looks broken.
    val unavailableMessage = stringResource(MR.string.chat_contact_card_action_unavailable)
    val openUri: (String) -> Unit = { uri ->
        if (runCatching { uriHandler.openUri(uri) }.isFailure) {
            scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
        }
    }

    // Pinned, not enterAlways: the close button is the only dismiss affordance and must not scroll
    // away. It still recolours the container once content sits behind it.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val paneName = stringResource(MR.string.chat_contact_card_detail_pane)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .semantics { paneTitle = paneName },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_contact_card_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(MR.string.chat_contact_card_close),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContactCardAvatar(
                    descriptor = descriptor,
                    size = 72.dp,
                    authorOdinId = authorOdinId,
                    sentByYou = sentByYou,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = descriptor.summaryLine()
                            .ifBlank { stringResource(MR.string.chat_contact_card_title) },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (onSaveToContacts != null) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { onSaveToContacts(descriptor) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = stringResource(MR.string.chat_contact_card_save))
                }
            }

            ValueSection(
                header = stringResource(MR.string.chat_contact_card_phones),
                values = descriptor.renderablePhones(),
                kind = ContactValueKind.Phone,
                actionLabel = stringResource(MR.string.chat_contact_card_call),
                onAction = { phone -> openUri("tel:${phone.telTarget()}") },
                copyLabel = MR.string.chat_contact_card_copy_phone,
                onCopy = copyValue,
                // An all-Arabic-Indic number builds `tel:` with nothing after it.
                canAct = { it.dialable().isNotBlank() },
                secondaryAction = { phone ->
                    ValueRowAction(
                        label = stringResource(MR.string.chat_contact_card_message, phone),
                        icon = Icons.AutoMirrored.Outlined.Message,
                        onClick = { openUri("sms:${phone.smsTarget()}") },
                    )
                },
            )

            ValueSection(
                header = stringResource(MR.string.chat_contact_card_emails),
                values = descriptor.renderableEmails(),
                kind = ContactValueKind.Email,
                actionLabel = stringResource(MR.string.chat_contact_card_send_email),
                onAction = { email -> openUri("mailto:${email.mailtoTarget()}") },
                copyLabel = MR.string.chat_contact_card_copy_email,
                onCopy = copyValue,
                // Otherwise "ada at example.com" still offers Send email and opens the mail
                // client on a recipient it cannot use.
                canAct = { it.looksLikeEmail() },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

private class ValueRowAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun ValueSection(
    header: String,
    values: List<String>,
    kind: ContactValueKind,
    actionLabel: String,
    onAction: (String) -> Unit,
    // A resource, not a string: every row's button would otherwise carry the same description, so
    // a three-phone card reads as three identical "Copy phone number" stops.
    copyLabel: StringResource,
    onCopy: (String) -> Unit,
    canAct: (String) -> Boolean = { true },
    secondaryAction: (@Composable (String) -> ValueRowAction)? = null,
) {
    if (values.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    Text(
        text = header,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
    values.forEach { value ->
        Spacer(Modifier.height(8.dp))
        val actionable = canAct(value)
        ValueRow(
            kind = kind,
            value = value,
            actionLabel = actionLabel.takeIf { actionable },
            onAction = if (actionable) ({ onAction(value) }) else null,
            secondary = if (actionable) secondaryAction?.invoke(value) else null,
            copyLabel = stringResource(copyLabel, value),
            onCopy = { onCopy(value) },
        )
    }
}

@Composable
private fun ValueRow(
    kind: ContactValueKind,
    value: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    secondary: ValueRowAction?,
    copyLabel: String,
    onCopy: () -> Unit,
) {
    Surface(
        // One node for the row either way: `clickable` merges its descendants, so a row with no
        // action has to say so itself or the value and its label read as two separate stops.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .let {
                // No onClickLabel: the visible action label below is already inside this merged
                // node, and TalkBack would otherwise read it twice.
                if (onAction != null) it.clickable(onClick = onAction)
                else it.semantics(mergeDescendants = true) {}
            },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = kind.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // No maxLines: this view exists to show the value in full. It scrolls. Drag-select
                // only reaches a row with no action — a clickable Surface consumes the press first
                // (see EventDetailDialog's ActionRow), which is why copy is a button too.
                SelectionContainer {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (actionLabel != null) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (secondary != null) {
                IconButton(onClick = secondary.onClick) {
                    Icon(
                        imageVector = secondary.icon,
                        contentDescription = secondary.label,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = copyLabel,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
