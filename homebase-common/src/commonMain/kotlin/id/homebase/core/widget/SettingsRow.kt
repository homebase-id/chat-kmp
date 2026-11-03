package id.homebase.core.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.cd_open_externally
import id.homebase.resources.collapse
import id.homebase.resources.expand
import org.jetbrains.compose.resources.stringResource

// The trailing affordance is derived from the variant, so an unmarked row can't be rendered.
sealed interface SettingsRowAction {
    data class Navigate(val onClick: () -> Unit) : SettingsRowAction

    data class External(val onClick: () -> Unit) : SettingsRowAction

    data class Toggle(
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : SettingsRowAction

    /** Discloses a list of [SettingsOptionRow]s below the row; the row itself picks nothing. */
    data class Expand(
        val expanded: Boolean,
        val onExpandedChange: (Boolean) -> Unit,
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
    containerColor: Color = MaterialTheme.colorScheme.surface,
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

                    // Announced as the row's click label, like External.
                    is SettingsRowAction.Expand -> {
                        val rotation = animateFloatAsState(
                            targetValue = if (action.expanded) 180f else 0f,
                            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                        )
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
                        )
                    }

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

        is SettingsRowAction.Expand -> modifier.clickable(
            onClickLabel = if (action.expanded) {
                stringResource(MR.string.collapse)
            } else {
                stringResource(MR.string.expand)
            },
            onClick = { action.onExpandedChange(!action.expanded) },
        )

        is SettingsRowAction.Invoke -> modifier.clickable(onClick = action.onClick)
    }

    // Not ListItem: it top-aligns its icon and control once the text passes two lines.
    Row(
        modifier = rowModifier
            .background(containerColor)
            .fillMaxWidth()
            .heightIn(min = if (supportingText == null) 56.dp else 72.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = contentColor,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            supportingText?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        trailing?.let { CompositionLocalProvider(LocalContentColor provides affordanceColor, content = it) }
    }
}
