package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.CircleMembersUi
import id.homebase.core.ui.screens.contactbook.ContactBookUiAction
import id.homebase.resources.MR
import id.homebase.resources.contactbook_circle_members_count
import id.homebase.resources.contactbook_circle_members_empty
import org.jetbrains.compose.resources.stringResource

@Composable
fun CircleMembersSheet(
    state: CircleMembersUi,
    onAction: (ContactBookUiAction) -> Unit,
) {
    AdaptiveSheet(onDismiss = { onAction(ContactBookUiAction.CircleMembersDismiss) }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = state.circleName,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.members.isEmpty() -> Text(
                    text = stringResource(MR.string.contactbook_circle_members_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )

                else -> {
                    Text(
                        text = stringResource(
                            MR.string.contactbook_circle_members_count, state.members.size
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(state.members, key = { it.uniqueId.toString() }) { entry ->
                            ContactBookRow(
                                entry = entry,
                                onClick = { onAction(ContactBookUiAction.ContactClicked(entry)) },
                                connected = true,
                            )
                        }
                    }
                }
            }
        }
    }
}
