package id.homebase.core.ui.screens.webdrop.components

import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import id.homebase.resources.MR
import id.homebase.resources.webdrop_view_only
import id.homebase.resources.webdrop_view_only_supporting
import org.jetbrains.compose.resources.stringResource

@Composable
fun WebDropViewOnlyRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(stringResource(MR.string.webdrop_view_only)) },
        supportingContent = { Text(stringResource(MR.string.webdrop_view_only_supporting)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}
