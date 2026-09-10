package id.homebase.core.ui.screens.email.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.email_settings_port
import id.homebase.resources.email_settings_security
import id.homebase.resources.email_settings_server
import id.homebase.resources.email_settings_username
import org.jetbrains.compose.resources.stringResource

/**
 * One server's settings, each value copyable. Copy matters more than it looks: these are typed
 * into a different application, often on a different device, and a mistyped hostname or the
 * wrong port produces a hang rather than an error message.
 */
@Composable
internal fun MailSettingsCard(
    title: String,
    host: String,
    port: Int,
    security: String,
    username: String,
    onCopy: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            SettingRow(stringResource(MR.string.email_settings_server), host, onCopy)
            SettingRow(stringResource(MR.string.email_settings_port), port.toString(), onCopy)
            SettingRow(stringResource(MR.string.email_settings_security), security, null)
            SettingRow(stringResource(MR.string.email_settings_username), username, onCopy)
        }
    }
}

/** A label/value pair. [onCopy] null for values nobody types, like "SSL". */
@Composable
private fun SettingRow(label: String, value: String, onCopy: ((String) -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (onCopy != null) {
            IconButton(onClick = { onCopy(value) }) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(MR.string.email_settings_server),
                )
            }
        }
    }
}
