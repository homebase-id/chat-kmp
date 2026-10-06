@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
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
import id.homebase.core.ui.screens.card.WithTooltip
import id.homebase.core.widget.connectedButtonShapes
import id.homebase.resources.MR
import id.homebase.resources.profile_edit_audience_circles_empty
import id.homebase.resources.profile_edit_audience_circles_hint
import id.homebase.resources.profile_edit_audience_circles_none
import id.homebase.resources.profile_edit_audience_only_me
import id.homebase.resources.profile_edit_audience_only_me_hint
import id.homebase.resources.profile_edit_audience_other_circles
import id.homebase.resources.moments_detail_shared_with_more
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

/** Each audience keeps one colour role everywhere it appears: the editor's segment, the row pill and the photo pill. */
private class AudienceColors(val container: Color, val content: Color, val icon: Color, val checked: Color, val onChecked: Color)

@Composable
private fun AudienceKind.colors(): AudienceColors {
    val c = MaterialTheme.colorScheme
    return when (this) {
        AudienceKind.Public -> AudienceColors(c.primaryContainer, c.onPrimaryContainer, c.primary, c.primary, c.onPrimary)
        AudienceKind.Circles -> AudienceColors(c.secondaryContainer, c.onSecondaryContainer, c.secondary, c.secondary, c.onSecondary)
        AudienceKind.OnlyMe -> AudienceColors(c.surfaceContainerHighest, c.onSurfaceVariant, c.outline, c.inverseSurface, c.inverseOnSurface)
    }
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
    val noCircles = circles.isEmpty()
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
                val colors = kind.colors()
                ToggleButton(
                    checked = checked,
                    enabled = !(kind == AudienceKind.Circles && noCircles && !checked),
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
                        checkedContainerColor = colors.checked,
                        checkedContentColor = colors.onChecked,
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier
                        .weight(1f)
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
        val selection = audience as? ProfileAudience.Circles
        AnimatedVisibility(
            visible = selection != null && !noCircles,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            val picked = selection ?: ProfileAudience.Circles(emptySet())
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                circles.forEach { circle ->
                    CircleChip(
                        name = circle.name,
                        selected = circle.id in picked.ids,
                        onToggle = { on ->
                            onChange(picked.copy(ids = if (on) picked.ids + circle.id else picked.ids - circle.id))
                        },
                    )
                }
            }
        }
        AudienceHint(audience = audience, noCircles = noCircles)
    }
}

/** One supporting line under the control: what the choice means, plus any circles kept that the chips can't show. */
@Composable
private fun AudienceHint(audience: ProfileAudience, noCircles: Boolean) {
    val emptyPick = !audience.isSavable && !noCircles
    val base = stringResource(
        when {
            audience is ProfileAudience.Circles && noCircles -> MR.string.profile_edit_audience_circles_none
            emptyPick -> MR.string.profile_edit_audience_circles_empty
            audience == ProfileAudience.Public -> MR.string.profile_edit_audience_public_hint
            audience == ProfileAudience.OnlyMe -> MR.string.profile_edit_audience_only_me_hint
            else -> MR.string.profile_edit_audience_circles_hint
        },
    )
    val others = (audience as? ProfileAudience.Circles)?.otherIds?.size ?: 0
    val extra = when {
        others > 0 -> " " + pluralStringResource(MR.plurals.profile_edit_audience_other_circles, others, others)
        // Says why the Circles segment is disabled.
        noCircles && audience !is ProfileAudience.Circles -> " " + stringResource(MR.string.profile_edit_audience_circles_none)
        else -> ""
    }
    Text(
        text = base + extra,
        style = MaterialTheme.typography.bodySmall,
        color = if (emptyPick) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CircleChip(name: String, selected: Boolean, onToggle: (Boolean) -> Unit) {
    val motion = MaterialTheme.motionScheme
    WithTooltip(name) {
        ToggleButton(
            checked = selected,
            onCheckedChange = onToggle,
            shapes = ToggleButtonDefaults.shapes(
                shape = MaterialTheme.shapes.medium,
                pressedShape = MaterialTheme.shapes.small,
                checkedShape = CircleShape,
            ),
            colors = ToggleButtonDefaults.toggleButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.widthIn(max = 280.dp).semantics { role = Role.Checkbox },
        ) {
            AnimatedVisibility(
                visible = selected,
                enter = expandHorizontally(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
                exit = shrinkHorizontally(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp).size(ToggleButtonDefaults.IconSize),
                )
            }
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Who sees a detail, as a tonal pill in its audience's colour role. */
@Composable
internal fun AudienceBadge(audience: ProfileAudience, circles: List<CardCircle>, modifier: Modifier = Modifier) {
    val kind = audience.kind
    val colors = kind.colors()
    Surface(
        shape = CircleShape,
        color = colors.container,
        contentColor = colors.content,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(kind.icon, contentDescription = null, tint = colors.icon, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = audienceSummary(audience, circles),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val SUMMARY_NAMES = 2

/** The one-line form of an audience: at most two circle names, then "+N". */
@Composable
internal fun audienceSummary(audience: ProfileAudience, circles: List<CardCircle>): String = when (audience) {
    ProfileAudience.Public -> stringResource(MR.string.profile_edit_visibility_public)
    ProfileAudience.OnlyMe -> stringResource(MR.string.profile_edit_audience_only_me)
    is ProfileAudience.Circles -> {
        val names = circles.filter { it.id in audience.ids }.map { it.name }
        val more = names.size - SUMMARY_NAMES
        when {
            names.isEmpty() -> stringResource(MR.string.profile_edit_visibility_circles)
            more > 0 -> names.take(SUMMARY_NAMES).joinToString(", ") + " " +
                stringResource(MR.string.moments_detail_shared_with_more, more)
            else -> names.joinToString(", ")
        }
    }
}

/** Whether a detail with this audience appears on the Public card ([circleId] null) or on that circle's card. */
internal fun ProfileAudience.isOnCard(circleId: String?): Boolean = when (this) {
    ProfileAudience.Public -> true
    ProfileAudience.OnlyMe -> false
    is ProfileAudience.Circles -> circleId != null && circleId in ids
}

internal val ProfileAudience.icon: ImageVector get() = kind.icon
