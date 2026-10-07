@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.core.ui.screens.card.CookieBadge
import id.homebase.core.ui.screens.card.WithTooltip
import id.homebase.core.widget.connectedButtonShapes
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.moments_detail_shared_with_more
import id.homebase.resources.profile_edit_audience_all_connections
import id.homebase.resources.profile_edit_audience_change
import id.homebase.resources.profile_edit_audience_circles_empty
import id.homebase.resources.profile_edit_audience_circles_hint
import id.homebase.resources.profile_edit_audience_circles_none
import id.homebase.resources.profile_edit_audience_only_me
import id.homebase.resources.profile_edit_audience_only_me_hint
import id.homebase.resources.profile_edit_audience_other_circle
import id.homebase.resources.profile_edit_audience_other_circles
import id.homebase.resources.profile_edit_audience_public_hint
import id.homebase.resources.profile_edit_audience_title
import id.homebase.resources.profile_edit_visibility_circles
import id.homebase.resources.profile_edit_visibility_public
import id.homebase.resources.save
import org.jetbrains.compose.resources.StringResource
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

/**
 * A role per audience for the pills that report it: Public filled, circles tonal with an edge,
 * Only me muted. Picking always uses the one selection colour.
 */
private class AudienceColors(val container: Color, val content: Color, val icon: Color, val edge: Color? = null)

@Composable
private fun AudienceKind.colors(): AudienceColors {
    val c = MaterialTheme.colorScheme
    return when (this) {
        AudienceKind.Public -> AudienceColors(c.primaryContainer, c.onPrimaryContainer, c.primary)
        AudienceKind.Circles -> AudienceColors(c.secondaryContainer, c.onSecondaryContainer, c.secondary, edge = c.outline)
        AudienceKind.OnlyMe -> AudienceColors(c.surfaceContainerHighest, c.onSurfaceVariant, c.onSurfaceVariant)
    }
}

/** Past this scale a connected row can't fit its labels, so its segments stack. */
private const val STACK_FONT_SCALE = 1.25f

/** Stacked segments keep the connected language: round outer ends, tight inner joins, a pill when picked. */
private fun stackedButtonShapes(index: Int, count: Int): ToggleButtonShapes {
    val outer = 24.dp
    val inner = 6.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return ToggleButtonShapes(
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        pressedShape = RoundedCornerShape(inner),
        checkedShape = CircleShape,
    )
}

/**
 * A connected single-select group: one row of joined segments, stacked when the text is large.
 * [item] gets the segment's shapes and the modifier that sizes it.
 */
@Composable
internal fun ConnectedChoices(
    count: Int,
    modifier: Modifier = Modifier,
    weight: (index: Int) -> Float = { 1f },
    item: @Composable (index: Int, shapes: ToggleButtonShapes, sizing: Modifier) -> Unit,
) {
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(
            modifier = modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            repeat(count) { item(it, stackedButtonShapes(it, count), Modifier.fillMaxWidth().heightIn(min = 48.dp)) }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            repeat(count) { item(it, connectedButtonShapes(it, count), Modifier.weight(weight(it)).heightIn(min = 48.dp)) }
        }
    }
}

/** A small caption that names a group of choices, so two groups in one panel can't be mistaken for each other. */
@Composable
internal fun ChoiceCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLargeEmphasized,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The one audience control every detail uses: Public | Circles | Only me, with a chip per Contacts
 * circle when Circles. [otherCircles] are stored circles without a card; they show as chips too, so
 * the user can see and remove them.
 */
@Composable
internal fun AudiencePicker(
    audience: ProfileAudience,
    circles: List<CardCircle>,
    onChange: (ProfileAudience) -> Unit,
    modifier: Modifier = Modifier,
    otherCircles: List<CardCircle> = emptyList(),
    showTitle: Boolean = true,
) {
    val motion = MaterialTheme.motionScheme
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) ChoiceCaption(stringResource(MR.string.profile_edit_audience_title))
        ConnectedChoices(count = AudienceKind.entries.size) { index, shapes, sizing ->
            val kind = AudienceKind.entries[index]
            ToggleButton(
                checked = audience.kind == kind,
                onCheckedChange = {
                    when (kind) {
                        AudienceKind.Public -> onChange(ProfileAudience.Public)
                        AudienceKind.OnlyMe -> onChange(ProfileAudience.OnlyMe)
                        AudienceKind.Circles ->
                            if (audience !is ProfileAudience.Circles) onChange(ProfileAudience.Circles(circles.map { it.id }.toSet()))
                    }
                },
                shapes = shapes,
                colors = ToggleButtonDefaults.toggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = sizing.semantics { role = Role.RadioButton },
            ) {
                Icon(
                    kind.icon,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp).size(ToggleButtonDefaults.IconSize),
                )
                Text(
                    text = stringResource(kind.label),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val selection = audience as? ProfileAudience.Circles
        AnimatedVisibility(
            visible = selection != null && (circles.isNotEmpty() || otherCircles.isNotEmpty()),
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            val picked = selection ?: ProfileAudience.Circles(emptySet())
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (circles.isNotEmpty()) {
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
                if (otherCircles.isNotEmpty()) {
                    Text(
                        text = stringResource(MR.string.profile_edit_audience_other_circles),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        otherCircles.forEach { circle ->
                            CircleChip(
                                name = circle.name,
                                selected = circle.id in picked.otherIds,
                                onToggle = { on ->
                                    onChange(picked.copy(otherIds = if (on) picked.otherIds + circle.id else picked.otherIds - circle.id))
                                },
                            )
                        }
                    }
                }
            }
        }
        AudienceHint(audience = audience, noCircles = circles.isEmpty())
    }
}

@Composable
private fun AudienceHint(audience: ProfileAudience, noCircles: Boolean) {
    val unsavable = !audience.isSavable
    val emptyPick = unsavable && !noCircles
    Text(
        text = stringResource(
            when {
                unsavable && noCircles -> MR.string.profile_edit_audience_circles_none
                emptyPick -> MR.string.profile_edit_audience_circles_empty
                audience == ProfileAudience.Public -> MR.string.profile_edit_audience_public_hint
                audience == ProfileAudience.OnlyMe -> MR.string.profile_edit_audience_only_me_hint
                else -> MR.string.profile_edit_audience_circles_hint
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (emptyPick) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CircleChip(name: String, selected: Boolean, onToggle: (Boolean) -> Unit) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
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
                containerColor = colors.surfaceContainerHighest,
                contentColor = colors.onSurface,
                checkedContainerColor = colors.secondaryContainer,
                checkedContentColor = colors.onSecondaryContainer,
            ),
            // An unpicked circle keeps an edge, so picked vs not reads by shape and fill, not by the tick alone.
            border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            modifier = Modifier.widthIn(max = 280.dp).heightIn(min = 48.dp).semantics { role = Role.Checkbox },
        ) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    (scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec())) togetherWith
                        (scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()))
                },
            ) { on ->
                Icon(
                    if (on) Icons.Outlined.Check else Icons.Outlined.Groups,
                    contentDescription = null,
                    tint = if (on) LocalContentColor.current else colors.secondary,
                    modifier = Modifier.padding(end = 8.dp).size(ToggleButtonDefaults.IconSize),
                )
            }
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * Who sees a detail, as a tonal pill in its audience's colour role. With [onClick] it is the way to
 * change that audience in place, so it gets a full touch target and a drop-down cue.
 */
@Composable
internal fun AudienceBadge(
    audience: ProfileAudience,
    circles: List<CardCircle>,
    modifier: Modifier = Modifier,
    otherNames: Map<String, String> = emptyMap(),
    onClick: (() -> Unit)? = null,
) {
    val kind = audience.kind
    val colors = kind.colors()
    val summary = audienceSummary(audience, circles, otherNames)
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = if (onClick != null) 4.dp else 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(kind.icon, contentDescription = null, tint = colors.icon, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onClick != null) {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
    val edge = colors.edge?.let { BorderStroke(1.dp, it) }
    if (onClick == null) {
        Surface(shape = CircleShape, color = colors.container, contentColor = colors.content, border = edge, modifier = modifier, content = content)
    } else {
        val description = stringResource(MR.string.profile_edit_audience_change, summary)
        Box(
            modifier = modifier
                .minimumInteractiveComponentSize()
                // Its own node, so a screen reader reaches it apart from the row it sits in.
                .semantics(mergeDescendants = true) {}
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick { onClick(); true }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Surface(onClick = onClick, shape = CircleShape, color = colors.container, contentColor = colors.content, border = edge, content = content)
        }
    }
}

private const val SUMMARY_NAMES = 2

/** The one-line form of an audience: at most two circle names, then "+N". */
@Composable
internal fun audienceSummary(
    audience: ProfileAudience,
    circles: List<CardCircle>,
    otherNames: Map<String, String> = emptyMap(),
): String = when (audience) {
    ProfileAudience.Public -> stringResource(MR.string.profile_edit_visibility_public)
    ProfileAudience.OnlyMe -> stringResource(MR.string.profile_edit_audience_only_me)
    is ProfileAudience.Circles -> {
        val other = stringResource(MR.string.profile_edit_audience_other_circle)
        val names = circles.filter { it.id in audience.ids }.map { it.name } +
            audience.otherIds.sorted().map { otherNames[it] ?: other }
        val more = names.size - SUMMARY_NAMES
        when {
            names.isEmpty() && circles.isEmpty() -> stringResource(MR.string.profile_edit_audience_all_connections)
            names.isEmpty() -> stringResource(MR.string.profile_edit_visibility_circles)
            more > 0 -> names.take(SUMMARY_NAMES).joinToString(", ") + " " +
                stringResource(MR.string.moments_detail_shared_with_more, more)
            else -> names.joinToString(", ")
        }
    }
}

/** Stored circles without a card that [audiences] hold, named where the name is known. */
@Composable
internal fun otherCirclesOf(vararg audiences: ProfileAudience, names: Map<String, String>): List<CardCircle> {
    val fallback = stringResource(MR.string.profile_edit_audience_other_circle)
    return audiences
        .flatMap { (it as? ProfileAudience.Circles)?.otherIds.orEmpty() }
        .distinct()
        .sorted()
        .map { CardCircle(it, names[it] ?: fallback) }
}

/** Below the anchor, centred in the window; above it when there is no room underneath. */
private class AnchoredPopoverPosition(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height - margin) {
            below
        } else {
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(margin)
        }
        return IntOffset(x, y)
    }
}

/**
 * The audience control on its own, floated over the page from a detail's audience pill, so who
 * sees it can change without opening the detail. Nothing is written until Save.
 */
@Composable
internal fun AudiencePopover(
    title: String,
    icon: ImageVector,
    initial: ProfileAudience,
    circles: List<CardCircle>,
    otherNames: Map<String, String>,
    onSave: (ProfileAudience) -> Unit,
    onDismiss: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    var draft by remember { mutableStateOf(initial) }
    val others = otherCirclesOf(initial, draft, names = otherNames)
    val density = LocalDensity.current
    val position = remember(density) {
        with(density) { AnchoredPopoverPosition(gap = 4.dp.roundToPx(), margin = 16.dp.roundToPx()) }
    }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = scaleIn(motion.defaultSpatialSpec(), initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 0f)) +
                fadeIn(motion.defaultEffectsSpec()),
            exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 440.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PanelHeader(icon = icon, title = title, subtitle = stringResource(MR.string.profile_edit_audience_title))
                    AudiencePicker(
                        audience = draft,
                        circles = circles,
                        onChange = { draft = it },
                        otherCircles = others,
                        showTitle = false,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(MR.string.cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = draft.isSavableWith(circles),
                            onClick = { onSave(draft) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(MR.string.save), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

/** A detail's icon in a cookie badge beside its name: the header of its editor and of its audience popover. */
@Composable
internal fun PanelHeader(icon: ImageVector, title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CookieIcon(icon, filled = true)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLargeEmphasized, color = colors.onSurface)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun CookieIcon(icon: ImageVector, filled: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    CookieBadge(
        icon = icon,
        container = if (filled) colors.secondary else colors.secondaryContainer,
        content = if (filled) colors.onSecondary else colors.onSecondaryContainer,
        size = 44.dp,
        iconSize = 22.dp,
    )
}

/** Whether a detail with this audience appears on the Public card ([circleId] null) or on that circle's card. */
internal fun ProfileAudience.isOnCard(circleId: String?): Boolean = when (this) {
    ProfileAudience.Public -> true
    ProfileAudience.OnlyMe -> false
    is ProfileAudience.Circles -> circleId != null && circleId in ids
}

internal val ProfileAudience.icon: ImageVector get() = kind.icon
