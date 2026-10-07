package id.homebase.core.ui.screens.webdrop.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.graphics.shapes.Morph
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.card.MorphShape
import id.homebase.resources.MR
import id.homebase.resources.webdrop_view_only
import id.homebase.resources.webdrop_view_only_badge
import id.homebase.resources.webdrop_view_only_supporting
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WebDropViewOnlyRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val progress by animateFloatAsState(if (checked) 1f else 0f, motion.defaultSpatialSpec())
    val corner by animateDpAsState(if (checked) 28.dp else 16.dp, motion.defaultSpatialSpec())
    val container by animateColorAsState(
        if (checked) colors.tertiaryContainer else colors.surfaceContainerHigh,
        motion.defaultSpatialSpec(),
    )
    val content by animateColorAsState(
        if (checked) colors.onTertiaryContainer else colors.onSurface,
        motion.defaultEffectsSpec(),
    )
    val badge by animateColorAsState(
        if (checked) colors.tertiary else colors.secondaryContainer,
        motion.defaultEffectsSpec(),
    )
    val badgeIcon by animateColorAsState(
        if (checked) colors.onTertiary else colors.onSecondaryContainer,
        motion.defaultEffectsSpec(),
    )
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }

    Surface(
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = content,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(badge, MorphShape(morph, progress)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Visibility, contentDescription = null, tint = badgeIcon, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(MR.string.webdrop_view_only),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = stringResource(MR.string.webdrop_view_only_supporting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (checked) colors.onTertiaryContainer else colors.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = colors.tertiary,
                    checkedThumbColor = colors.onTertiary,
                    checkedIconColor = colors.tertiary,
                    checkedBorderColor = colors.tertiary,
                ),
                thumbContent = if (checked) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
fun WebDropViewOnlyBadge(muted: Boolean = false, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelMedium
    val tint = if (muted) colors.onSurfaceVariant else colors.onTertiaryContainer
    Row(
        modifier = modifier
            .then(
                if (muted) Modifier.border(1.dp, colors.outlineVariant, CircleShape)
                else Modifier.background(colors.tertiaryContainer, CircleShape)
            )
            .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Outlined.Visibility,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(with(LocalDensity.current) { style.fontSize.toDp() * 1.2f }),
        )
        Text(text = stringResource(MR.string.webdrop_view_only_badge), style = style, color = tint)
    }
}
