package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.resources.MR
import id.homebase.resources.contactbook_connected
import id.homebase.resources.contactbook_self_you
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactBookRow(
    entry: ContactBookEntry,
    onClick: () -> Unit,
    connected: Boolean = false,
    /** When non-null, the row renders dimmed, is unclickable, and this text replaces the normal
     *  subtitle line — e.g. explaining why an otherwise-visible contact can't be picked here. */
    disabledReason: String? = null,
    /** Optional trailing content (e.g. a pending-request marker). Replaces the connected check. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val disabled = disabledReason != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactBookAvatar(entry = entry)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            val name = if (entry.isSelf) {
                stringResource(MR.string.contactbook_self_you, entry.displayName)
            } else {
                entry.displayName
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = disabledReason ?: entry.subtitle
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        } else if (connected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = stringResource(MR.string.contactbook_connected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
