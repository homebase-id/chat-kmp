package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.components.ContactBookRow
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.resources.MR
import id.homebase.resources.contactbook_connections_empty
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectionsTabContent(
    connections: List<ContactBookEntry>,
    onAction: (ContactBookUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (connections.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(MR.string.contactbook_connections_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(connections, key = { it.uniqueId.toString() }) { entry ->
            ContactBookRow(
                entry = entry,
                onClick = { onAction(ContactBookUiAction.ContactClicked(entry)) },
                connected = true,
            )
        }
    }
}
