package id.homebase.core.ui.screens.contactbook.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.ui.screens.contactbook.components.CirclePickerChips
import id.homebase.core.ui.screens.contactbook.components.ContactBookAvatar
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.resources.MR
import id.homebase.resources.contactbook_detail_accept
import id.homebase.resources.contactbook_detail_reject
import id.homebase.resources.contactbook_detail_request_incoming
import org.jetbrains.compose.resources.stringResource

/**
 * Self-contained public-profile card shown in place of the tabbed contact detail while an
 * incoming connection request is still pending (#921). Everything it shows is public and
 * fetchable before connecting — avatar (`/pub/image`), display name, Homebase ID, status, and
 * short bio summary — so the Accept/Reject decision has real context instead of the empty
 * "Contact details: None" / "connect to see…" placeholders.
 *
 * This owns the whole pending presentation (rather than reusing the shared header + tabs) so the
 * pre-connection state's logic lives in one place — it is the *only* place the detail screen
 * offers Accept/Reject, since the header renders only once the request is gone. Accepting flips
 * the parent screen to the full connected detail in place — no navigation.
 */
@Composable
fun PendingRequestProfile(
    entry: ContactBookEntry,
    assignableCircles: List<ContactCircleUi>,
    onAccept: (selectedCircleIds: List<String>) -> Unit,
    onReject: () -> Unit,
    actionInProgress: Boolean,
    onAvatarClick: (SubSamplingImageSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Circle ids the user has ticked to add this contact to on Accept. Keyed by the contact so it
    // resets when viewing a different request (a transient picker — no need to survive process death).
    var selectedCircleIds by remember(entry.uniqueId) { mutableStateOf(emptySet<String>()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ContactBookAvatar(entry = entry, size = 112.dp, onClick = onAvatarClick)

        Spacer(modifier = Modifier.height(12.dp))

        // Name + Homebase ID are selectable so they can be copied.
        SelectionContainer {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
        }
        entry.odinId?.let { odinId ->
            SelectionContainer {
                Text(
                    text = odinId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(MR.string.contactbook_detail_request_incoming),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )

        // Free-text status/tagline the identity set on their public profile.
        entry.status?.takeIf { it.isNotBlank() }?.let { status ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }

        // Public short-bio summary, when the identity has published one.
        entry.shortBio?.takeIf { it.isNotBlank() }?.let { bio ->
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                SelectionContainer {
                    Text(
                        text = bio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        // Optional: pick which of the user's own circles to add this contact to on Accept.
        // The circles ride the accept request atomically (see AcceptConnectionRequestV2).
        if (assignableCircles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            CirclePickerChips(
                circles = assignableCircles,
                selectedIds = selectedCircleIds,
                onToggle = { id ->
                    selectedCircleIds = if (id in selectedCircleIds) {
                        selectedCircleIds - id
                    } else {
                        selectedCircleIds + id
                    }
                },
                enabled = !actionInProgress,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onReject,
                enabled = !actionInProgress,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(MR.string.contactbook_detail_reject))
            }
            FilledTonalButton(
                onClick = { onAccept(selectedCircleIds.toList()) },
                enabled = !actionInProgress,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(MR.string.contactbook_detail_accept))
            }
        }
    }
}
