package id.homebase.core.ui.screens.email.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilledTonalButton
import id.homebase.api.client.mail.MailAppStatus
import id.homebase.api.client.mail.MailboxStatusResult
import id.homebase.core.email.MailClientDescriptor
import id.homebase.core.email.canLaunchMailClient
import id.homebase.resources.MR
import id.homebase.resources.email_home_address_label
import id.homebase.resources.email_home_secrets
import id.homebase.resources.email_client_none
import id.homebase.resources.email_home_client
import id.homebase.resources.email_home_secrets_detail
import id.homebase.resources.email_home_status_ok
import id.homebase.resources.email_mailbox_junk
import id.homebase.resources.email_mailbox_none_unread
import id.homebase.resources.email_mailbox_open_client
import id.homebase.resources.email_mailbox_queued
import id.homebase.resources.email_mailbox_unread
import id.homebase.resources.email_no_server_retry
import org.jetbrains.compose.resources.stringResource

/**
 * The screen once email works: the address, that it is working, and the way in to the secrets.
 *
 * Storage deliberately is not here yet — the mail server does not report usage on this setup, and
 * a row that always says "unknown" is worse than no row.
 */
@Composable
fun EmailHomeContent(
    status: MailAppStatus?,
    mailbox: MailboxStatusResult?,
    selectedClient: MailClientDescriptor?,
    onOpenSecrets: () -> Unit,
    onOpenClientPicker: () -> Unit,
    onRefresh: () -> Unit,
    onOpenMailClient: () -> Unit,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.MailOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = status?.primaryEmailAddress ?: "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(MR.string.email_home_address_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(MR.string.email_home_status_ok),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                // Only when the mail server actually answered — showing "0 unread" because the
                // question failed would be a lie the user would act on.
                if (mailbox?.available == true) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (mailbox.inboxUnread > 0) {
                            stringResource(MR.string.email_mailbox_unread, mailbox.inboxUnread)
                        } else {
                            stringResource(MR.string.email_mailbox_none_unread)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    if (mailbox.junkTotal > 0) {
                        Text(
                            text = stringResource(MR.string.email_mailbox_junk, mailbox.junkTotal),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Anything queued is a delivery problem, so it gets the error colour rather
                    // than sitting quietly with the other counts.
                    if (mailbox.queuedOutbound > 0) {
                        Text(
                            text = stringResource(MR.string.email_mailbox_queued, mailbox.queuedOutbound),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (selectedClient != null && canLaunchMailClient(selectedClient)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(onClick = onOpenMailClient) {
                            Text(stringResource(MR.string.email_mailbox_open_client, selectedClient.displayName))
                        }
                    }
                }

                status?.publicKeyFingerprint?.let { fingerprint ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        // Short form: enough to compare against a mail client at a glance.
                        text = fingerprint.takeLast(16).chunked(4).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onRefresh, enabled = !isRefreshing) {
                    Text(stringResource(MR.string.email_no_server_retry))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenClientPicker)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.MailOutline,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(MR.string.email_home_client),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = selectedClient?.displayName ?: stringResource(MR.string.email_client_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSecrets)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.VpnKey,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(MR.string.email_home_secrets),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(MR.string.email_home_secrets_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
