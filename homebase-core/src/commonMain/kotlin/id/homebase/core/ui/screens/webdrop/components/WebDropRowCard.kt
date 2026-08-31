package id.homebase.core.ui.screens.webdrop.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.core.ui.screens.webdrop.model.DropRow
import id.homebase.core.ui.screens.webdrop.model.DropStatus
import id.homebase.resources.MR
import id.homebase.resources.webdrop_copy
import id.homebase.resources.webdrop_files_count
import id.homebase.resources.webdrop_for_label
import id.homebase.resources.webdrop_revoke
import id.homebase.resources.webdrop_row_clear
import id.homebase.resources.webdrop_status_expires
import id.homebase.resources.webdrop_status_opened
import id.homebase.resources.webdrop_status_removed
import id.homebase.resources.webdrop_status_waiting
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

private fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}

@Composable
fun WebDropRowCard(
    row: DropRow,
    onCopyLink: () -> Unit,
    onRevoke: () -> Unit,
    onClear: () -> Unit,
) {
    val status = row.status
    var nowMs by remember { mutableLongStateOf(UnixTimeUtc.now().milliseconds) }
    if (status is DropStatus.Opened || status is DropStatus.Expiring) {
        LaunchedEffect(row.dropId) {
            while (true) {
                nowMs = UnixTimeUtc.now().milliseconds
                delay(1000)
            }
        }
    }

    val statusText = when (status) {
        DropStatus.Waiting -> stringResource(MR.string.webdrop_status_waiting)
        is DropStatus.Opened ->
            stringResource(MR.string.webdrop_status_opened, formatRemaining(status.diesAtMs - nowMs))
        is DropStatus.Expiring ->
            stringResource(MR.string.webdrop_status_expires, formatRemaining(status.diesAtMs - nowMs))
        DropStatus.Removed -> stringResource(MR.string.webdrop_status_removed)
    }
    val removed = status == DropStatus.Removed

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.receipt.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (removed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                row.receipt.recipientName?.let { name ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(MR.string.webdrop_for_label, name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(MR.string.webdrop_files_count, row.receipt.files.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (status) {
                        is DropStatus.Opened -> MaterialTheme.colorScheme.error
                        DropStatus.Removed -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }

            if (removed) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.string.webdrop_row_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                IconButton(onClick = onCopyLink) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(MR.string.webdrop_copy),
                    )
                }
                IconButton(onClick = onRevoke) {
                    Icon(
                        imageVector = Icons.Outlined.LinkOff,
                        contentDescription = stringResource(MR.string.webdrop_revoke),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
