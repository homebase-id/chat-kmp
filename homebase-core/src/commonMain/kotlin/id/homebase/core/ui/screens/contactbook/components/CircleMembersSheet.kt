package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.CircleMembersUi
import id.homebase.core.ui.screens.contactbook.ContactBookUiAction
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.circle_member_pending
import id.homebase.resources.contactbook_circle_add_member
import id.homebase.resources.circle_member_remove_cd
import id.homebase.resources.circle_member_remove_confirm_body
import id.homebase.resources.circle_member_remove_confirm_title
import id.homebase.resources.contactbook_circle_members_count
import id.homebase.resources.contactbook_circle_members_count_with_pending
import id.homebase.resources.contactbook_circle_members_empty
import id.homebase.resources.remove
import org.jetbrains.compose.resources.stringResource

@Composable
fun CircleMembersSheet(
    state: CircleMembersUi,
    onAction: (ContactBookUiAction) -> Unit,
) {
    var confirmRemove by remember { mutableStateOf<ContactBookEntry?>(null) }

    AdaptiveSheet(onDismiss = { onAction(ContactBookUiAction.CircleMembersDismiss) }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.circleName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.manageable) {
                    IconButton(onClick = {
                        onAction(ContactBookUiAction.CircleAddMemberClicked(state.circleId, state.circleName))
                    }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(MR.string.contactbook_circle_add_member))
                    }
                }
            }
            val allMembers = state.members + state.pendingMembers
            val pendingIds = remember(state.pendingMembers) { state.pendingMembers.map { it.uniqueId }.toSet() }
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                allMembers.isEmpty() && !state.pendingChecking -> Text(
                    text = stringResource(MR.string.contactbook_circle_members_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )

                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (state.pendingMembers.isEmpty()) {
                                stringResource(MR.string.contactbook_circle_members_count, state.members.size)
                            } else {
                                stringResource(
                                    MR.string.contactbook_circle_members_count_with_pending,
                                    state.members.size,
                                    state.pendingMembers.size,
                                )
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.pendingChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(start = 8.dp).heightIn(max = 12.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(allMembers, key = { it.uniqueId.toString() }) { entry ->
                            ContactBookRow(
                                entry = entry,
                                onClick = { onAction(ContactBookUiAction.ContactClicked(entry)) },
                                trailing = if (state.manageable) {
                                    {
                                        CircleMemberTrailing(
                                            pending = pendingIds.contains(entry.uniqueId),
                                            onRemoveClick = { confirmRemove = entry },
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
        }
    }

    confirmRemove?.let { member ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(MR.string.circle_member_remove_confirm_title)) },
            text = {
                Text(stringResource(MR.string.circle_member_remove_confirm_body, member.displayName, state.circleName))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    onAction(ContactBookUiAction.CircleRemoveMemberClicked(state.circleId, member))
                }) { Text(stringResource(MR.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        )
    }
}

/** Trailing content for a circle-member row: an optional "Pending" label (a sealed deposit
 *  that hasn't converted into a real grant yet) plus a remove button. */
@Composable
private fun CircleMemberTrailing(pending: Boolean, onRemoveClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (pending) {
            Text(
                text = stringResource(MR.string.circle_member_pending),
                style = MaterialTheme.typography.labelMedium,
                color = HomebaseTheme.extendedColors.warning,
            )
        }
        IconButton(onClick = onRemoveClick) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(MR.string.circle_member_remove_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
