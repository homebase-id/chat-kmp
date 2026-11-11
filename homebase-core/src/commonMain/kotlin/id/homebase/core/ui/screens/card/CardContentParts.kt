@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalUuidApi::class, ExperimentalLayoutApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import id.homebase.core.widget.MorphShape
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.profile.ATTRIBUTE_SPECS
import id.homebase.core.ui.screens.profile.AttributeFields
import id.homebase.core.ui.screens.profile.AudienceBadge
import id.homebase.core.ui.screens.profile.EditorActions
import id.homebase.core.ui.screens.profile.PanelHeader
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.screens.profile.ProfileField
import id.homebase.core.ui.screens.profile.isAttributeValid
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.connectedButtonShapes
import id.homebase.resources.MR
import id.homebase.resources.profile_card_content_add_new
import id.homebase.resources.profile_card_content_add_title
import id.homebase.resources.profile_card_content_add_visible
import id.homebase.resources.profile_card_content_empty
import id.homebase.resources.profile_card_content_shown_everyone
import id.homebase.resources.profile_card_content_shown_to
import id.homebase.resources.profile_card_content_sees_circle
import id.homebase.resources.profile_card_content_sees_public
import id.homebase.resources.profile_edit_add_group_social
import id.homebase.resources.profile_edit_bio
import id.homebase.resources.profile_edit_email
import id.homebase.resources.profile_edit_link
import id.homebase.resources.profile_edit_link_target
import id.homebase.resources.profile_edit_link_text
import id.homebase.resources.profile_edit_phone
import kotlin.uuid.ExperimentalUuidApi
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid
import id.homebase.core.ui.screens.profile.ConflictRow
import id.homebase.core.ui.screens.profile.conflictValue
import id.homebase.core.ui.screens.profile.audienceSummary
import id.homebase.core.ui.screens.profile.audience
import id.homebase.core.ui.screens.profile.isOnCard
import id.homebase.resources.profile_card_content_edit_title
import id.homebase.resources.profile_card_content_delete
import id.homebase.resources.profile_card_audience_public
import id.homebase.resources.profile_card_sheet_available_on
import androidx.compose.ui.platform.testTag
import id.homebase.resources.profile_card_content_more
import id.homebase.core.ui.screens.profile.PROFILE_TYPE_FIELDS

internal enum class AddKind(val type: String) {
    Phone(ProfileAttributeTypes.PHONE),
    Email(ProfileAttributeTypes.EMAIL),
    Link(ProfileAttributeTypes.LINK),
    Social(ProfileAttributeTypes.TWITTER),
    Bio(ProfileAttributeTypes.BIO_SUMMARY),
    More(""),
}

// The kinds the add group has no button of its own for.
internal val MORE_TYPES = listOf(
    ProfileAttributeTypes.NAME,
    ProfileAttributeTypes.NICKNAME,
    ProfileAttributeTypes.STATUS,
    ProfileAttributeTypes.BIRTHDAY,
    ProfileAttributeTypes.ADDRESS,
)

// The Board button's inset, so the step's header, heading, rows and add group share one start edge.
internal val SECTION_INSET = 24.dp
internal val GROUP_OUTER_CORNER = 20.dp
internal val GROUP_INNER_CORNER = 4.dp
private val LIFTED_CORNER = 28.dp
internal val ROW_GAP = 2.dp
internal val BADGE_SIZE = 40.dp
internal val BADGE_GAP = 16.dp
private val HEADING_BADGE_SIZE = 56.dp
private val ADD_PADDING = 12.dp

@Composable
internal fun AudienceHeading(card: CardAudience, emoji: String? = null) {
    val text = when (card) {
        CardAudience.Public -> stringResource(MR.string.profile_card_content_shown_everyone)
        is CardAudience.Circle -> stringResource(MR.string.profile_card_content_shown_to, audienceLabel(card))
    }
    val sees = stringResource(if (card is CardAudience.Circle) MR.string.profile_card_content_sees_circle else MR.string.profile_card_content_sees_public)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() },
    ) {
        CookieBadge(
            icon = audienceIcon(card),
            container = audienceContainer(card),
            content = onAudienceContainer(card),
            size = HEADING_BADGE_SIZE,
            iconSize = 28.dp,
            emoji = emoji,
        )
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sees,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun addIcon(kind: AddKind): ImageVector = when (kind) {
    AddKind.Social -> Icons.Outlined.Share
    AddKind.More -> Icons.Outlined.MoreHoriz
    else -> typeIcon(kind.type)
}

// Only what can be added shows; a kind that can't be added is left out rather than shown disabled.
// Rows that don't fit the width wrap into a second connected group rather than scrolling off the edge.
@Composable
internal fun AddGroup(
    isAvailable: (AddKind) -> Boolean,
    enabled: Boolean,
    onAdd: (AddKind) -> Unit,
) {
    val kinds = AddKind.entries.filter(isAvailable)
    if (kinds.isEmpty()) return
    val labels = kinds.map { stringResource(addLabel(it)) }
    val title = stringResource(MR.string.profile_card_content_add_new)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widths = labels.map { label ->
            with(density) { measurer.measure(label, style).size.width.toDp() } + ADD_PADDING * 2 + ButtonDefaults.IconSize + ButtonDefaults.IconSpacing
        }
        val rows = mutableListOf(mutableListOf<Int>())
        var used = 0.dp
        widths.forEachIndexed { index, width ->
            val gap = if (rows.last().isEmpty()) 0.dp else ButtonGroupDefaults.ConnectedSpaceBetween
            if (rows.last().isNotEmpty() && used + gap + width > maxWidth) {
                rows += mutableListOf(index)
                used = width
            } else {
                rows.last() += index
                used += gap + width
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    row.forEachIndexed { position, index ->
                        val toggle = connectedButtonShapes(position, row.size)
                        FilledTonalButton(
                            onClick = { onAdd(kinds[index]) },
                            enabled = enabled,
                            shapes = ButtonShapes(shape = toggle.shape, pressedShape = toggle.pressedShape),
                            contentPadding = PaddingValues(horizontal = ADD_PADDING),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .heightIn(min = ButtonDefaults.MinHeight)
                                .semantics { contentDescription = "$title: ${labels[index]}" },
                        ) {
                            Icon(addIcon(kinds[index]), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(labels[index], maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AddSheet(
    isAvailable: (AddKind) -> Boolean,
    onAdd: (AddKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val kinds = AddKind.entries.filter(isAvailable)
    val title = stringResource(MR.string.profile_card_content_add_new)
    AdaptiveSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            PanelHeader(icon = Icons.Outlined.Add, title = stringResource(MR.string.profile_card_content_add_title), modifier = Modifier.padding(bottom = 12.dp))
            kinds.forEachIndexed { index, kind ->
                val label = stringResource(addLabel(kind))
                androidx.compose.material3.Surface(
                    onClick = { dismiss { onDismiss(); onAdd(kind) } },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = groupedShape(index, kinds.size),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$title: $label" },
                ) {
                    Row(
                        modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BADGE_GAP),
                    ) {
                        Icon(addIcon(kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

// The empty card's one job is getting something onto it, so the add choices sit right in the prompt.
@Composable
internal fun EmptyContent(add: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(GROUP_OUTER_CORNER),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(MR.string.profile_card_content_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            add()
        }
    }
}

// The M3 Expressive grouped list: one rounded block whose rows meet with tight inner corners; a lifted row rounds out fully.
internal fun groupedShape(index: Int, count: Int, lift: Float = 0f): RoundedCornerShape {
    fun corner(outer: Boolean) = androidx.compose.ui.unit.lerp(if (outer) GROUP_OUTER_CORNER else GROUP_INNER_CORNER, LIFTED_CORNER, lift)
    return RoundedCornerShape(
        topStart = corner(index == 0),
        topEnd = corner(index == 0),
        bottomStart = corner(index == count - 1),
        bottomEnd = corner(index == count - 1),
    )
}

// Off is an outlined circle, on is a filled cookie: the badge morphs between them on the spatial spring as the switch flips.
@Composable
internal fun ShownBadge(icon: ImageVector, shown: Boolean) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val progress by animateFloatAsState(if (shown) 1f else 0f, motion.defaultSpatialSpec())
    val fill by animateColorAsState(if (shown) colors.secondary else colors.surfaceContainerHigh, motion.defaultEffectsSpec())
    val tint by animateColorAsState(if (shown) colors.onSecondary else colors.onSurfaceVariant, motion.defaultEffectsSpec())
    val shape = MorphShape(morph, progress.coerceIn(0f, 1f))
    Box(modifier = Modifier.size(BADGE_SIZE), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    val pop = 0.9f + 0.1f * progress
                    scaleX = pop
                    scaleY = pop
                }
                .background(fill, shape)
                .border(1.5.dp, colors.outline.copy(alpha = (1f - progress).coerceIn(0f, 1f)), shape),
        )
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * Adds an item ([editing] null, shown on [card]) or edits one: its fields, who sees it, older conflicting
 * records, and Delete, which takes it off every card.
 */
@Composable
internal fun ContentSheet(
    type: String,
    card: CardAudience,
    circles: List<CardCircle>,
    onDismiss: () -> Unit,
    editing: CardContentItem? = null,
    socials: List<String> = emptyList(),
    onAdd: (String, Map<String, String>) -> Unit = { _, _ -> },
    onEdit: (Map<String, String>, ProfileAudience) -> Unit = { _, _ -> },
    onDelete: ((Uuid) -> Unit)? = null,
) {
    // Opens expanded: from the half-height anchor the keyboard covers the field and Save.
    AdaptiveSheet(onDismiss = onDismiss, expandFully = true) {
        var shownType by remember { mutableStateOf(type) }
        val data = remember(shownType) { mutableStateMapOf<String, String>().apply { editing?.let { putAll(it.data) } } }
        var audience by remember(editing?.id) { mutableStateOf(editing?.audience ?: card.newItemAudience()) }
        val link = shownType == ProfileAttributeTypes.LINK
        val linkUrl = data[ProfileAttributeTypes.KEY_LINK_TARGET].orEmpty().trim().let { if (it.isEmpty() || "://" in it) it else "https://$it" }
        val fields = PROFILE_TYPE_FIELDS[shownType].orEmpty()
        fun value(field: ProfileField) = fields.firstOrNull { it.first == field }?.let { data[it.second] }.orEmpty()
        val main = fields.firstOrNull()?.first
        val filled = if (link) linkUrl.isNotEmpty() && isWebUrl(linkUrl) else main != null && value(main).isNotBlank()
        val canSave = filled && (link || isAttributeValid(shownType, ::value)) && audience.isSavableWith(circles)
        val updates = if (link) {
            mapOf(ProfileAttributeTypes.KEY_LINK_TEXT to data[ProfileAttributeTypes.KEY_LINK_TEXT].orEmpty(), ProfileAttributeTypes.KEY_LINK_TARGET to linkUrl)
        } else {
            fields.associate { (_, key) -> key to data[key].orEmpty() }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PanelHeader(
                icon = typeIcon(shownType),
                title = stringResource(typeLabel(shownType)),
                subtitle = stringResource(if (editing == null) MR.string.profile_card_content_add_title else MR.string.profile_card_content_edit_title),
            )
            if (editing == null && socials.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    socials.forEach { social ->
                        FilterChip(
                            selected = social == shownType,
                            onClick = { shownType = social },
                            label = { Text(stringResource(typeLabel(social))) },
                        )
                    }
                }
            }
            if (link) {
                ProfileField(
                    data[ProfileAttributeTypes.KEY_LINK_TEXT].orEmpty(),
                    stringResource(MR.string.profile_edit_link_text),
                    modifier = Modifier.fillMaxWidth(),
                ) { data[ProfileAttributeTypes.KEY_LINK_TEXT] = it }
                ProfileField(
                    data[ProfileAttributeTypes.KEY_LINK_TARGET].orEmpty(),
                    stringResource(MR.string.profile_edit_link_target),
                    keyboardType = KeyboardType.Uri,
                    ltr = true,
                    modifier = Modifier.fillMaxWidth(),
                ) { data[ProfileAttributeTypes.KEY_LINK_TARGET] = it }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AttributeFields(shownType, ::value) { field, v ->
                        fields.firstOrNull { it.first == field }?.let { data[it.second] = v }
                    }
                }
            }
            if (editing == null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AudienceBadge(audience = audience, circles = circles)
                    Text(
                        text = stringResource(MR.string.profile_card_content_add_visible, audienceLabel(card)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = stringResource(MR.string.profile_card_sheet_available_on),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pills = listOf<Triple<CardAudience, String, String?>>(
                        Triple(CardAudience.Public, stringResource(MR.string.profile_card_audience_public), null),
                    ) + circles.map { Triple(CardAudience.Circle(it.id, it.name), it.name, it.emoji) }
                    pills.forEach { (pillCard, label, emoji) ->
                        val selected = when (pillCard) {
                            CardAudience.Public -> audience == ProfileAudience.Public
                            is CardAudience.Circle -> audience.isOnCard(pillCard.id)
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { audience = audience.shownOn(pillCard, !selected, editing.audience, circles) ?: audience },
                            label = { Text(listOfNotNull(emoji, label).joinToString(" ")) },
                            modifier = Modifier.testTag("audience_pill_$label"),
                        )
                    }
                }
                editing.conflicts.forEach { record ->
                    ConflictRow(
                        value = conflictValue(record.type, record),
                        audience = audienceSummary(record.audience(circles), circles),
                        onDiscard = { onDelete?.invoke(record.id) },
                    )
                }
            }
            EditorActions(
                canSave = canSave,
                onSave = {
                    dismiss {
                        if (editing == null) onAdd(shownType, updates) else onEdit(updates, audience)
                        onDismiss()
                    }
                },
                onCancel = { dismiss() },
                onRemove = if (editing != null && onDelete != null && !editing.required && editing.type != ProfileAttributeTypes.NAME) {
                    { dismiss { onDelete(editing.id); onDismiss() } }
                } else {
                    null
                },
                removeLabel = stringResource(MR.string.profile_card_content_delete),
            )
        }
    }
}

private fun addLabel(kind: AddKind) = when (kind) {
    AddKind.Phone -> MR.string.profile_edit_phone
    AddKind.Email -> MR.string.profile_edit_email
    AddKind.Link -> MR.string.profile_edit_link
    AddKind.Social -> MR.string.profile_edit_add_group_social
    AddKind.Bio -> MR.string.profile_edit_bio
    AddKind.More -> MR.string.profile_card_content_more
}

internal fun typeIcon(type: String): ImageVector =
    ATTRIBUTE_SPECS.firstOrNull { it.type == type }?.icon ?: Icons.Outlined.Link

internal fun typeLabel(type: String) =
    ATTRIBUTE_SPECS.firstOrNull { it.type == type }?.labelRes ?: MR.string.profile_edit_link
