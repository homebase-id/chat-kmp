package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.contactbook.detail.ContactCircleUi
import id.homebase.resources.MR
import id.homebase.resources.contactbook_detail_add_to_circles
import org.jetbrains.compose.resources.stringResource

/**
 * "Add to circles" multi-select chip row shown alongside an incoming connection request's
 * Accept button. The chosen circle ids ride the accept call atomically (see
 * [id.homebase.api.client.connections.AcceptConnectionRequestV2]) rather than being applied as
 * follow-up membership writes.
 *
 * Renders nothing when [circles] is empty — an identity with no user-defined circles gets the
 * plain Accept button. Selection is owned by the caller so it can be keyed to the identity in
 * view and reset when that changes.
 *
 * @param centered centers the label and chips (the full-screen pending-request profile); false
 *   start-aligns them to match the Add Contact form's left-aligned fields.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CirclePickerChips(
    circles: List<ContactCircleUi>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    centered: Boolean = true,
) {
    if (circles.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(MR.string.contactbook_detail_add_to_circles),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        ) {
            circles.forEach { circle ->
                val selected = circle.id in selectedIds
                FilterChip(
                    selected = selected,
                    enabled = enabled,
                    onClick = { onToggle(circle.id) },
                    label = { Text(circle.name) },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
