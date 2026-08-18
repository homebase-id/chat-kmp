package id.homebase.core.widget

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// One choice under a [SettingsRowAction.Expand] row. The empty leading slot lines the label up
// with its parent's, and `selectable` announces the selection instead of the check mark.
@Composable
fun SettingsOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        headlineContent = {
            Text(text = label, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Spacer(modifier = Modifier.size(24.dp)) },
        trailingContent = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(
            headlineColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            trailingIconColor = MaterialTheme.colorScheme.primary,
        ),
    )
}
