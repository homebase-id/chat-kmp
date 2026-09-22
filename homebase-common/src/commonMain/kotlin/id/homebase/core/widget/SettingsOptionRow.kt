package id.homebase.core.widget

import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow

// The radio occupies the same 24.dp leading slot as SettingsRow's icon, so an option nested under
// a [SettingsRowAction.Expand] row still lines up with its parent's label.
@Composable
fun SettingsOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
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
        supportingContent = supportingText?.let { supporting ->
            { Text(text = supporting) }
        },
        leadingContent = {
            RadioButton(selected = selected, onClick = null)
        },
    )
}
