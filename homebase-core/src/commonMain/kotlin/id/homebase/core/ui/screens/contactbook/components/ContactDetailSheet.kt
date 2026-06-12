package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.ContactBookUiAction
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.resources.MR
import id.homebase.resources.contactbook_detail_delete
import id.homebase.resources.contactbook_detail_edit
import id.homebase.resources.contactbook_detail_message
import id.homebase.resources.contactbook_detail_sync
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailSheet(
    entry: ContactBookEntry,
    onAction: (ContactBookUiAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ContactBookAvatar(entry = entry, size = 80.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            entry.odinId?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            DetailField(Icons.Outlined.Call, entry.phone)
            DetailField(Icons.Outlined.Email, entry.email)
            DetailField(Icons.Outlined.LocationOn, entry.location)
            DetailField(Icons.Outlined.Cake, entry.birthday)

            Spacer(modifier = Modifier.height(16.dp))

            if (entry.hasOdinId) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onAction(ContactBookUiAction.MessageClicked(entry)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(MR.string.contactbook_detail_message))
                    }
                    OutlinedButton(
                        onClick = { onAction(ContactBookUiAction.SyncClicked(entry)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(MR.string.contactbook_detail_sync))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onAction(ContactBookUiAction.EditClicked(entry)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(MR.string.contactbook_detail_edit))
                }
                TextButton(
                    onClick = { onAction(ContactBookUiAction.DeleteClicked(entry)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(MR.string.contactbook_detail_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailField(icon: ImageVector, value: String?) {
    if (value.isNullOrBlank()) return
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(value) },
    )
}
