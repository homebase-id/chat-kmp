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
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import id.homebase.api.client.mail.MailAppHealth
import id.homebase.api.client.mail.MailAppStatus
import id.homebase.api.client.mail.MailHealthSeverity
import id.homebase.api.client.mail.MailboxMode
import id.homebase.api.client.mail.MailboxStatusResult
import id.homebase.core.email.Thunderbird
import id.homebase.core.email.canLaunchMailClient
import id.homebase.core.ui.screens.email.thunderbird.Body
import id.homebase.core.ui.screens.email.thunderbird.SetupCard
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.email_health_attention
import id.homebase.resources.email_health_fix_hint
import id.homebase.resources.email_health_unavailable
import id.homebase.resources.email_home_address_label
import id.homebase.resources.email_home_mail_app
import id.homebase.resources.email_home_mail_app_detail
import id.homebase.resources.email_home_secrets
import id.homebase.resources.email_home_secrets_detail
import id.homebase.resources.email_home_thunderbird
import id.homebase.resources.email_home_thunderbird_detail
import id.homebase.resources.email_mailbox_junk
import id.homebase.resources.email_mailbox_none_unread
import id.homebase.resources.email_mailbox_open_client
import id.homebase.resources.email_mailbox_queued
import id.homebase.resources.email_mailbox_unread
import id.homebase.resources.email_open_owner_console
import id.homebase.resources.email_refresh
import id.homebase.resources.email_server_checking
import id.homebase.resources.email_server_header
import id.homebase.resources.email_server_no_report
import id.homebase.resources.email_server_running
import id.homebase.resources.email_setup_check_again
import id.homebase.resources.email_setup_checking
import id.homebase.resources.email_setup_dnssec_broken
import id.homebase.resources.email_setup_dnssec_missing
import id.homebase.resources.email_setup_header
import id.homebase.resources.email_setup_ok
import id.homebase.resources.email_setup_warning
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The screen once email is set up: the address, the server and its mail, whether the domain is
 * set up so mail reaches it, and the way in to the secrets.
 *
 * Storage deliberately is not here yet — the mail server does not report usage on this setup, and
 * a row that always says "unknown" is worse than no row.
 */
@Composable
fun EmailHomeContent(
    status: MailAppStatus?,
    mailbox: MailboxStatusResult?,
    onOpenSecrets: () -> Unit,
    onOpenThunderbirdSetup: () -> Unit,
    onRefresh: () -> Unit,
    onOpenMailClient: () -> Unit,
    isRefreshing: Boolean,
    health: MailAppHealth?,
    isCheckingHealth: Boolean,
    healthUnavailable: Boolean,
    onCheckHealth: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenOwnerConsole: () -> Unit,
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

        // Two questions, two cards: is the server up and is there mail, and is the domain set up
        // so mail reaches it. One green tick for both hid a domain with no MX behind a working
        // server.
        EmailServerCard(
            status = status,
            mailbox = mailbox,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            onOpenMailClient = onOpenMailClient,
        )

        Spacer(modifier = Modifier.height(16.dp))

        EmailSetupCard(
            health = health,
            isCheckingHealth = isCheckingHealth,
            healthUnavailable = healthUnavailable,
            onCheckHealth = onCheckHealth,
            onCopy = onCopy,
            onOpenOwnerConsole = onOpenOwnerConsole,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // The secrets screen leads with the server settings and passwords any mail app needs
        if (status?.effectiveMode == MailboxMode.Standard) {
            NavigationRow(
                icon = Icons.Outlined.MailOutline,
                title = stringResource(MR.string.email_home_mail_app),
                detail = stringResource(MR.string.email_home_mail_app_detail),
                onClick = onOpenSecrets,
            )
        } else {
            NavigationRow(
                icon = Icons.Outlined.Lock,
                title = stringResource(MR.string.email_home_thunderbird),
                detail = stringResource(MR.string.email_home_thunderbird_detail),
                onClick = onOpenThunderbirdSetup,
            )
            NavigationRow(
                icon = Icons.Outlined.VpnKey,
                title = stringResource(MR.string.email_home_secrets),
                detail = stringResource(MR.string.email_home_secrets_detail),
                onClick = onOpenSecrets,
            )
        }
    }
}

@Composable
private fun EmailServerCard(
    status: MailAppStatus?,
    mailbox: MailboxStatusResult?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenMailClient: () -> Unit,
) {
    SetupCard(title = stringResource(MR.string.email_server_header)) {
        // Counts only when the mail server actually answered: "0 unread" because the question
        // failed would be a lie the user would act on.
        val answered = mailbox?.takeIf { it.available }
        val (icon, tint, line) = when {
            mailbox == null -> Triple(Icons.Outlined.Info, MaterialTheme.colorScheme.onSurfaceVariant, MR.string.email_server_checking)
            answered == null -> Triple(Icons.Outlined.Info, MaterialTheme.colorScheme.onSurfaceVariant, MR.string.email_server_no_report)
            else -> Triple(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, MR.string.email_server_running)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(busy = isRefreshing || mailbox == null, icon = icon, tint = tint)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(line), style = MaterialTheme.typography.bodyLarge)
                if (answered != null) {
                    Text(
                        text = if (answered.inboxUnread > 0) {
                            pluralStringResource(MR.plurals.email_mailbox_unread, answered.inboxUnread, answered.inboxUnread)
                        } else {
                            stringResource(MR.string.email_mailbox_none_unread)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Next to the line it refreshes, and there even when the server did not answer:
            // that is when a refresh matters most.
            IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(MR.string.email_refresh),
                )
            }
        }

        if (answered != null) {
            if (answered.junkTotal > 0) {
                Body(pluralStringResource(MR.plurals.email_mailbox_junk, answered.junkTotal, answered.junkTotal))
            }

            // Anything queued is a delivery problem, so it gets the error colour rather than
            // sitting quietly with the other counts.
            if (answered.queuedOutbound > 0) {
                Text(
                    text = pluralStringResource(MR.plurals.email_mailbox_queued, answered.queuedOutbound, answered.queuedOutbound),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (canLaunchMailClient(Thunderbird.client)) {
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(onClick = onOpenMailClient) {
                    Text(stringResource(MR.string.email_mailbox_open_client, Thunderbird.client.displayName))
                }
            }
        }

        // Short form: enough to compare against a mail client at a glance.
        status?.publicKeyFingerprint?.let { Body(it.takeLast(16).chunked(4).joinToString(" ")) }
    }
}

/**
 * Whether the domain is set up so mail reaches it and is not marked as spam. The colour is the
 * server's [MailAppHealth.severity] — the same checks the owner console runs, decided there and
 * only rendered here.
 */
@Composable
private fun EmailSetupCard(
    health: MailAppHealth?,
    isCheckingHealth: Boolean,
    healthUnavailable: Boolean,
    onCheckHealth: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenOwnerConsole: () -> Unit,
) {
    SetupCard(title = stringResource(MR.string.email_setup_header)) {
        // A failed check must never read as a clean bill of health.
        val shown = health?.takeUnless { healthUnavailable }
        val (icon, tint, headline) = when (shown?.severity) {
            null -> Triple(
                Icons.Outlined.Info,
                MaterialTheme.colorScheme.onSurfaceVariant,
                if (healthUnavailable) MR.string.email_health_unavailable else MR.string.email_setup_checking,
            )
            MailHealthSeverity.Ok -> Triple(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, MR.string.email_setup_ok)
            MailHealthSeverity.Warning -> Triple(Icons.Outlined.WarningAmber, HomebaseTheme.extendedColors.warning, MR.string.email_setup_warning)
            MailHealthSeverity.Error -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, MR.string.email_health_attention)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(busy = isCheckingHealth, icon = icon, tint = tint)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(headline),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }

        if (shown != null && shown.severity != MailHealthSeverity.Ok) {
            shown.brokenRecords.forEach { record ->
                Spacer(modifier = Modifier.height(12.dp))
                MailDnsRecordRow(record = record, onCopy = onCopy)
            }
            shown.errors.forEach { Body(it) }
            shown.warnings.forEach { Body(it) }
            shown.dnssec?.let { dnssec ->
                when {
                    dnssec.breaksResolution -> Body(stringResource(MR.string.email_setup_dnssec_broken))
                    dnssec.needsAttention -> Body(stringResource(MR.string.email_setup_dnssec_missing))
                }
            }
            // Publishing DNS is an owner action, so open the owner console on the page that
            // does it rather than duplicating a write button in the app. For a domain whose DNS
            // is hosted elsewhere, the records above are the instructions.
            Body(stringResource(MR.string.email_health_fix_hint))
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(onClick = onOpenOwnerConsole) {
                Text(stringResource(MR.string.email_open_owner_console))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onCheckHealth, enabled = !isCheckingHealth) {
            Text(stringResource(MR.string.email_setup_check_again))
        }
    }
}

/** A section's status icon, or a spinner in its place while the section is being re-asked. */
@Composable
private fun StatusIcon(busy: Boolean, icon: ImageVector, tint: Color) {
    if (busy) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    } else {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun NavigationRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = detail,
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
