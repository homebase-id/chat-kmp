package id.homebase.core.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import id.homebase.core.util.isExpandedLayout
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.resources.MR
import id.homebase.resources.settings_navigation_bar_switch_wide
import org.jetbrains.compose.resources.stringResource

/** An app's "Show X in the navigation bar" switch. On a wide layout the rail shows every app, which it says. */
@Composable
internal fun NavigationBarSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Visibility,
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    SettingsRow(
        modifier = modifier,
        icon = icon,
        title = title,
        supportingText = if (isExpandedLayout()) stringResource(MR.string.settings_navigation_bar_switch_wide) else null,
        containerColor = containerColor,
        action = SettingsRowAction.Toggle(checked = checked, onCheckedChange = onCheckedChange),
    )
}
