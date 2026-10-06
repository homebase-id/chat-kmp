@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.core.widget.connectedButtonShapes
import id.homebase.resources.MR
import id.homebase.resources.profile_edit_audience_circles_empty
import id.homebase.resources.profile_edit_audience_circles_hint
import id.homebase.resources.profile_edit_audience_circles_none
import id.homebase.resources.profile_edit_audience_only_me
import id.homebase.resources.profile_edit_audience_only_me_hint
import id.homebase.resources.profile_edit_audience_other_circles
import id.homebase.resources.profile_edit_audience_public_hint
import id.homebase.resources.profile_edit_audience_title
import id.homebase.resources.profile_edit_visibility_circles
import id.homebase.resources.profile_edit_visibility_public
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private enum class AudienceKind(val icon: ImageVector, val label: StringResource) {
    Public(Icons.Outlined.Public, MR.string.profile_edit_visibility_public),
    Circles(Icons.Outlined.Groups, MR.string.profile_edit_visibility_circles),
    OnlyMe(Icons.Outlined.Lock, MR.string.profile_edit_audience_only_me),
}

private val ProfileAudience.kind: AudienceKind
    get() = when (this) {
        ProfileAudience.Public -> AudienceKind.Public
        ProfileAudience.OnlyMe -> AudienceKind.OnlyMe
        is ProfileAudience.Circles -> AudienceKind.Circles
    }

@Composable
private fun AudienceKind.tint(): Color = when (this) {
    AudienceKind.Public -> MaterialTheme.colorScheme.primary
    AudienceKind.Circles -> MaterialTheme.colorScheme.tertiary
    AudienceKind.OnlyMe -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The one audience control every detail uses: Public | Circles | Only me, with a chip per Contacts circle when Circles. */
@Composable
internal fun AudiencePicker(
    audience: ProfileAudience,
    circles: List<CardCircle>,
    onChange: (ProfileAudience) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(MR.string.profile_edit_audience_title),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            AudienceKind.entries.forEachIndexed { index, kind ->
                val checked = audience.kind == kind
                // The chosen segment widens to make room for its icon, so all three labels still fit at large font scales.
                val weight by animateFloatAsState(if (checked) 1.35f else 1f, motion.fastSpatialSpec())
                ToggleButton(
                    checked = checked,
                    onCheckedChange = {
                        when (kind) {
                            AudienceKind.Public -> onChange(ProfileAudience.Public)
                            AudienceKind.OnlyMe -> onChange(ProfileAudience.OnlyMe)
                            AudienceKind.Circles ->
                                if (audience !is ProfileAudience.Circles) onChange(ProfileAudience.Circles(circles.map { it.id }.toSet()))
                        }
                    },
                    shapes = connectedButtonShapes(index, AudienceKind.entries.size),
                    colors = ToggleButtonDefaults.toggleButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier
                        .weight(weight)
                        .heightIn(min = 48.dp)
                        .semantics { role = Role.RadioButton },
                ) {
                    AnimatedVisibility(
                        visible = checked,
                        enter = expandHorizontally(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
                        exit = shrinkHorizontally(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                    ) {
                        Icon(
                            kind.icon,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp).size(ToggleButtonDefaults.IconSize),
                        )
                    }
                    Text(
                        text = stringResource(kind.label),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = audience is ProfileAudience.Circles,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            val selection = audience as? ProfileAudience.Circles ?: ProfileAudience.Circles(emptySet())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (circles.isEmpty()) {
                    Text(
                        text = stringResource(MR.string.profile_edit_audience_circles_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        circles.forEach { circle ->
                            CircleChip(
                                name = circle.name,
                                selected = circle.id in selection.ids,
                                onToggle = { on ->
                                    onChange(selection.copy(ids = if (on) selection.ids + circle.id else selection.ids - circle.id))
                                },
                            )
                        }
                    }
                }
                if (selection.otherIds.isNotEmpty()) {
                    Text(
                        text = pluralStringResource(MR.plurals.profile_edit_audience_other_circles, selection.otherIds.size, selection.otherIds.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val isEmptySelection = !audience.isSavable
        // With no circles to pick, the "none" line above already says why; a second error would only repeat it.
        if (isEmptySelection && circles.isEmpty()) return@Column
        Text(
            text = stringResource(
                when {
                    isEmptySelection -> MR.string.profile_edit_audience_circles_empty
                    audience == ProfileAudience.Public -> MR.string.profile_edit_audience_public_hint
                    audience == ProfileAudience.OnlyMe -> MR.string.profile_edit_audience_only_me_hint
                    else -> MR.string.profile_edit_audience_circles_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (isEmptySelection) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CircleChip(name: String, selected: Boolean, onToggle: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    )
}

/** Who sees a detail, as a quiet pill: the same icon and tint as its choice in [AudiencePicker]. */
@Composable
internal fun AudienceBadge(audience: ProfileAudience, circles: List<CardCircle>, modifier: Modifier = Modifier) {
    val kind = audience.kind
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(kind.icon, contentDescription = null, tint = kind.tint(), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = audienceSummary(audience, circles),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The one-line form of an audience a collapsed row shows. */
@Composable
internal fun audienceSummary(audience: ProfileAudience, circles: List<CardCircle>): String = when (audience) {
    ProfileAudience.Public -> stringResource(MR.string.profile_edit_visibility_public)
    ProfileAudience.OnlyMe -> stringResource(MR.string.profile_edit_audience_only_me)
    is ProfileAudience.Circles -> circles.filter { it.id in audience.ids }.joinToString(", ") { it.name }
        .ifEmpty { stringResource(MR.string.profile_edit_visibility_circles) }
}
