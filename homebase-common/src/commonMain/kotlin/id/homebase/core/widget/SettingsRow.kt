package id.homebase.core.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.cd_open_externally
import org.jetbrains.compose.resources.stringResource

// The trailing affordance is derived from the variant, so an unmarked row can't be rendered.
sealed interface SettingsRowAction {
    data class Navigate(val onClick: () -> Unit) : SettingsRowAction

    data class External(val onClick: () -> Unit) : SettingsRowAction

    data class Toggle(
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : SettingsRowAction

    /** Runs here and now, with no onward destination to point at. */
    data class Invoke(val onClick: () -> Unit) : SettingsRowAction
}

// [status] renders alongside the derived affordance, never instead of it.
// [isDestructive] is weight only, so a destructive row still derives its affordance from [action].
@Composable
fun SettingsRow(
    /** Use `Icons.Outlined.*` — every settings screen in the app is on the outlined family. */
    icon: ImageVector,
    title: String,
    action: SettingsRowAction,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isDestructive: Boolean = false,
    status: (@Composable () -> Unit)? = null,
) {
    val contentColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val affordanceColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val hasAffordance = action !is SettingsRowAction.Invoke
    val trailing: (@Composable () -> Unit)? = if (!hasAffordance && status == null) {
        null
    } else {
        {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                status?.invoke()
                when (action) {
                    is SettingsRowAction.Navigate -> Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        // The row itself is the announced target; the chevron adds nothing to it.
                        contentDescription = null,
                    )

                    // Announced as the row's click label instead, so it can't land between
                    // the headline and the supporting text.
                    is SettingsRowAction.External -> Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                    )

                    is SettingsRowAction.Toggle -> Switch(
                        checked = action.checked,
                        onCheckedChange = null,
                    )

                    is SettingsRowAction.Invoke -> Unit
                }
            }
        }
    }

    val rowModifier = when (action) {
        is SettingsRowAction.Toggle -> modifier.toggleable(
            value = action.checked,
            onValueChange = action.onCheckedChange,
            role = Role.Switch,
        )

        is SettingsRowAction.Navigate -> modifier.clickable(onClick = action.onClick)
        is SettingsRowAction.External -> modifier.clickable(
            onClickLabel = stringResource(MR.string.cd_open_externally),
            onClick = action.onClick,
        )
        is SettingsRowAction.Invoke -> modifier.clickable(onClick = action.onClick)
    }

    ListItem(
        modifier = rowModifier,
        headlineContent = {
            Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = supportingText?.let { supporting ->
            { Text(text = supporting, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(
            headlineColor = contentColor,
            leadingIconColor = contentColor,
            trailingIconColor = affordanceColor,
        ),
    )
}
