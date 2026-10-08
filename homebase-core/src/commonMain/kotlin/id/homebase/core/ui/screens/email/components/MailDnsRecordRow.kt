package id.homebase.core.ui.screens.email.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import id.homebase.api.client.mail.MailDnsRecord
import id.homebase.resources.MR
import id.homebase.resources.email_copy_name
import id.homebase.resources.email_copy_value
import id.homebase.resources.email_record_at
import id.homebase.resources.email_record_not_found
import id.homebase.resources.email_record_unchecked
import id.homebase.resources.email_record_wrong_value
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One DNS record the server found missing or wrong: where it goes, what is wrong with it, and the
 * value to publish, each copyable into a DNS provider.
 */
@Composable
fun MailDnsRecordRow(
    record: MailDnsRecord,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // What the record is for: several broken TXT records look alike without it.
        if (record.description.isNotBlank()) {
            Text(
                text = record.description,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        CopyableLine(
            text = stringResource(MR.string.email_record_at, record.type, record.domain),
            // The label, not the FQDN: DNS providers append the zone to whatever is pasted into
            // the host field, so the FQDN would end up published at name.example.com.example.com.
            copyText = record.name.ifBlank { "@" },
            copyLabel = stringResource(MR.string.email_copy_name),
            onCopy = onCopy,
        )
        Text(
            text = stringResource(problemOf(record.status)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (record.value.isNotBlank()) {
            CopyableLine(
                text = record.value,
                copyText = record.value,
                copyLabel = stringResource(MR.string.email_copy_value),
                onCopy = onCopy,
                monospace = true,
            )
        }
    }
}

@Composable
private fun CopyableLine(
    text: String,
    copyText: String,
    copyLabel: String,
    onCopy: (String) -> Unit,
    monospace: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onCopy(copyText) }) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = copyLabel,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Words for the server's lookup verdict (odin-core `DnsLookupRecordStatus`), not a new one. */
private fun problemOf(status: String): StringResource = when (status) {
    "domainOrRecordNotFound" -> MR.string.email_record_not_found
    "incorrectValue" -> MR.string.email_record_wrong_value
    else -> MR.string.email_record_unchecked
}
