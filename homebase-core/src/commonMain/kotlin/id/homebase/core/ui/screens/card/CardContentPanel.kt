@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalUuidApi::class, ExperimentalLayoutApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.graphics.shapes.Morph
import id.homebase.resources.profile_card_content_change_audience
import id.homebase.resources.profile_card_content_name_required
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.profile.ATTRIBUTE_SPECS
import id.homebase.core.ui.screens.profile.AttributeFields
import id.homebase.core.ui.screens.profile.AudienceBadge
import id.homebase.core.ui.screens.profile.AudiencePicker
import id.homebase.core.ui.screens.profile.CookieIcon
import id.homebase.core.ui.screens.profile.EditorActions
import id.homebase.core.ui.screens.profile.PanelHeader
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.screens.profile.ProfileEditViewModel
import id.homebase.core.ui.screens.profile.ProfileField
import id.homebase.core.ui.screens.profile.isAttributeValid
import id.homebase.core.ui.screens.profile.otherCirclesOf
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.resources.MR
import id.homebase.resources.profile_card_content_add_title
import id.homebase.resources.profile_card_content_add_visible
import id.homebase.resources.profile_card_content_empty
import id.homebase.resources.profile_card_content_on_card
import id.homebase.resources.profile_card_content_public_hint
import id.homebase.resources.profile_card_content_switch
import id.homebase.resources.profile_edit_add_group_social
import id.homebase.resources.profile_edit_audience_title
import id.homebase.resources.profile_edit_bio
import id.homebase.resources.profile_edit_email
import id.homebase.resources.profile_edit_link
import id.homebase.resources.profile_edit_link_target
import id.homebase.resources.profile_edit_link_text
import id.homebase.resources.profile_edit_phone
import id.homebase.resources.profile_edit_visibility_public
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource

private enum class AddKind(val type: String) {
    Phone(ProfileAttributeTypes.PHONE),
    Email(ProfileAttributeTypes.EMAIL),
    Link(ProfileAttributeTypes.LINK),
    Social(ProfileAttributeTypes.TWITTER),
    Bio(ProfileAttributeTypes.BIO_SUMMARY),
}

private val SECTION_INSET = 16.dp
private val GROUP_OUTER_CORNER = 20.dp
private val GROUP_INNER_CORNER = 4.dp
private val ROW_GAP = 2.dp
private val BADGE_SIZE = 40.dp
private val BADGE_GAP = 16.dp

@Composable
internal fun CardContentPanel(
    card: CardAudience,
    items: List<CardContentItem>,
    circles: List<CardCircle>,
    enabled: Boolean,
    onToggle: (Uuid, Boolean) -> Unit,
    onAudience: (Uuid, ProfileAudience) -> Unit,
    onAdd: (String, Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var audienceFor by remember { mutableStateOf<Uuid?>(null) }
    var adding by remember { mutableStateOf<AddKind?>(null) }
    var pending by remember { mutableStateOf<Uuid?>(null) }
    LaunchedEffect(enabled) { if (enabled) pending = null }
    val present = items.map { it.type }.toSet()
    val missingSocials = CARD_SOCIAL_TYPES.filter { it !in present }
    val addable = AddKind.entries.filter { kind ->
        when (kind) {
            AddKind.Link -> true
            AddKind.Social -> missingSocials.isNotEmpty()
            else -> kind.type !in present
        }
    }

    // The title and shortcuts scroll away with the list, so a short screen gives its height to the rows.
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fadingEdges(scroll, vertical = true)
            .verticalScroll(scroll)
            .padding(horizontal = SECTION_INSET)
            .padding(bottom = 8.dp),
    ) {
        Text(
            text = stringResource(MR.string.profile_card_content_on_card, audienceLabel(card)),
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() },
        )
        if (addable.isNotEmpty()) {
            AddRow(kinds = addable, wrap = items.isEmpty(), onAdd = { adding = it })
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            if (items.isEmpty()) EmptyContent()
            items.forEachIndexed { index, item ->
                ContentRow(
                    item = item,
                    enabled = enabled,
                    pending = !enabled && pending == item.id,
                    shape = groupedShape(index, items.size),
                    onOpenAudience = { audienceFor = item.id },
                    onToggle = {
                        pending = item.id
                        onToggle(item.id, it)
                    },
                )
            }
        }
    }

    items.firstOrNull { it.id == audienceFor }?.let { item ->
        AudienceSheet(
            item = item,
            circles = circles,
            onApply = { audience ->
                pending = item.id
                onAudience(item.id, audience)
            },
            onDismiss = { audienceFor = null },
        )
    }
    adding?.let { kind ->
        AddContentSheet(
            kind = kind,
            card = card,
            circles = circles,
            socials = missingSocials,
            onAdd = onAdd,
            onDismiss = { adding = null },
        )
    }
}

// Independent actions, so separate tonal buttons rather than a connected group, which here means "pick one".
// With nothing listed they wrap so every shortcut shows; otherwise one scrolling line keeps the rows in view.
@Composable
private fun AddRow(kinds: List<AddKind>, wrap: Boolean, onAdd: (AddKind) -> Unit) {
    val title = stringResource(MR.string.profile_card_content_add_title)
    val buttons: @Composable () -> Unit = {
        kinds.forEach { kind ->
            val label = stringResource(addLabel(kind))
            FilledTonalButton(
                onClick = { onAdd(kind) },
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "$title: $label" },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MinHeight)))
                Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(ButtonDefaults.MinHeight)))
                Text(label, maxLines = 1, softWrap = false)
            }
        }
    }
    val spacing = Arrangement.spacedBy(8.dp)
    if (wrap) {
        FlowRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = spacing) { buttons() }
    } else {
        val scroll = rememberScrollState()
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).fadingEdges(scroll).horizontalScroll(scroll),
            horizontalArrangement = spacing,
        ) { buttons() }
    }
}

@Composable
private fun EmptyContent() {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(GROUP_OUTER_CORNER),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CookieIcon(Icons.Outlined.Dashboard)
            Text(
                text = stringResource(MR.string.profile_card_content_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

// The M3 Expressive grouped list: one rounded block whose rows meet with tight inner corners.
private fun groupedShape(index: Int, count: Int) = RoundedCornerShape(
    topStart = if (index == 0) GROUP_OUTER_CORNER else GROUP_INNER_CORNER,
    topEnd = if (index == 0) GROUP_OUTER_CORNER else GROUP_INNER_CORNER,
    bottomStart = if (index == count - 1) GROUP_OUTER_CORNER else GROUP_INNER_CORNER,
    bottomEnd = if (index == count - 1) GROUP_OUTER_CORNER else GROUP_INNER_CORNER,
)

private val PROSE_TYPES = setOf(ProfileAttributeTypes.NAME, ProfileAttributeTypes.BIO_SUMMARY)

@Composable
private fun ContentRow(
    item: CardContentItem,
    enabled: Boolean,
    pending: Boolean,
    shape: Shape,
    onOpenAudience: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(typeLabel(item.type))
    val switchLabel = stringResource(MR.string.profile_card_content_switch, item.text)
    val fixed = item.locked || item.required
    val changeAudience = stringResource(MR.string.profile_card_content_change_audience, label)
    val hint = stringResource(MR.string.profile_card_content_public_hint)
    // A locked row is one button that says why and opens the audience: the only way off a circle card is to stop being Public.
    val toggle = if (item.locked) {
        Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpenAudience)
            .semantics {
                contentDescription = "$changeAudience. ${item.text}"
                stateDescription = hint
            }
    } else if (item.required) {
        Modifier.semantics(mergeDescendants = true) {}
    } else {
        Modifier
            .toggleable(value = item.shown, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .semantics { contentDescription = switchLabel }
    }
    // Numbers, addresses and links stay on one line: wrapping them on punctuation makes a different number.
    val prose = item.type in PROSE_TYPES
    Surface(color = colors.surfaceContainerHigh, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = toggle.heightIn(min = 64.dp).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShownBadge(icon = typeIcon(item.type), shown = item.shown)
            Spacer(Modifier.width(BADGE_GAP))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.text,
                    // User content keeps its own direction; a phone number has none, so it falls back to LTR rather than reversing.
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr),
                    color = colors.onSurface,
                    maxLines = if (prose) 2 else 1,
                    softWrap = prose,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    item.locked -> RowHint(icon = Icons.Outlined.Public, text = hint, action = true)
                    item.required -> RowHint(icon = Icons.Filled.Lock, text = stringResource(MR.string.profile_card_content_name_required), action = false)
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = item.shown,
                onCheckedChange = null,
                thumbContent = {
                    when {
                        pending -> LoadingIndicator(modifier = Modifier.size(SwitchDefaults.IconSize))
                        fixed -> Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize))
                        item.shown -> Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize))
                    }
                },
            )
        }
    }
}

// The row's own reason; an [action] hint is primary, since tapping the row changes it.
@Composable
private fun RowHint(icon: ImageVector, text: String, action: Boolean) {
    val color = if (action) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Off is an outlined circle, on is a filled cookie: the badge morphs between them on the spatial spring as the switch flips.
@Composable
private fun ShownBadge(icon: ImageVector, shown: Boolean) {
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

// Applied as the sheet closes, not through a second Save: the change is the choice itself.
@Composable
private fun AudienceSheet(
    item: CardContentItem,
    circles: List<CardCircle>,
    onApply: (ProfileAudience) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(item.id) { mutableStateOf(item.audience) }
    val close = {
        if (draft != item.audience && draft.isSavableWith(circles)) onApply(draft)
        onDismiss()
    }
    AdaptiveSheet(onDismiss = close) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PanelHeader(
                icon = typeIcon(item.type),
                title = stringResource(typeLabel(item.type)),
                subtitle = stringResource(MR.string.profile_edit_audience_title),
            )
            AudiencePicker(
                audience = draft,
                circles = circles,
                onChange = { draft = it },
                otherCircles = otherCirclesOf(item.audience, draft, names = emptyMap()),
                showTitle = false,
            )
        }
    }
}

@Composable
private fun AddContentSheet(
    kind: AddKind,
    card: CardAudience,
    circles: List<CardCircle>,
    socials: List<String>,
    onAdd: (String, Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        var type by remember { mutableStateOf(if (kind == AddKind.Social) socials.firstOrNull() ?: kind.type else kind.type) }
        val values = remember(type) { mutableStateMapOf<ProfileField, String>() }
        var linkText by remember { mutableStateOf("") }
        var linkTarget by remember { mutableStateOf("") }
        val linkUrl = linkTarget.trim().let { if ("://" in it) it else "https://$it" }
        val main = ProfileEditViewModel.TYPE_FIELDS[type]?.firstOrNull()?.first
        val canSave = if (type == ProfileAttributeTypes.LINK) {
            linkTarget.isNotBlank() && isWebUrl(linkUrl)
        } else {
            main != null && values[main].orEmpty().isNotBlank() && isAttributeValid(type) { values[it].orEmpty() }
        }
        val updates = if (type == ProfileAttributeTypes.LINK) {
            mapOf(ProfileAttributeTypes.KEY_LINK_TEXT to linkText, ProfileAttributeTypes.KEY_LINK_TARGET to linkUrl)
        } else {
            ProfileEditViewModel.TYPE_FIELDS[type].orEmpty().associate { (field, key) -> key to values[field].orEmpty() }
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PanelHeader(icon = typeIcon(type), title = stringResource(typeLabel(type)), subtitle = stringResource(MR.string.profile_card_content_add_title))
            if (kind == AddKind.Social && socials.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    socials.forEach { social ->
                        FilterChip(
                            selected = social == type,
                            onClick = { type = social },
                            label = { Text(stringResource(typeLabel(social))) },
                        )
                    }
                }
            }
            if (type == ProfileAttributeTypes.LINK) {
                ProfileField(
                    linkText,
                    stringResource(MR.string.profile_edit_link_text),
                    modifier = Modifier.fillMaxWidth(),
                ) { linkText = it }
                ProfileField(
                    linkTarget,
                    stringResource(MR.string.profile_edit_link_target),
                    keyboardType = KeyboardType.Uri,
                    ltr = true,
                    modifier = Modifier.fillMaxWidth(),
                ) { linkTarget = it }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AttributeFields(type, { values[it].orEmpty() }) { field, value -> values[field] = value }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AudienceBadge(audience = card.newItemAudience(), circles = circles)
                Text(
                    text = stringResource(MR.string.profile_card_content_add_visible, audienceLabel(card)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EditorActions(
                canSave = canSave,
                onSave = { dismiss { onAdd(type, updates); onDismiss() } },
                onCancel = { dismiss() },
                onRemove = null,
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
}

private fun addIcon(kind: AddKind): ImageVector = typeIcon(
    when (kind) {
        AddKind.Social -> ProfileAttributeTypes.INSTAGRAM
        else -> kind.type
    },
)

private fun typeIcon(type: String): ImageVector =
    ATTRIBUTE_SPECS.firstOrNull { it.type == type }?.icon ?: Icons.Outlined.Link

private fun typeLabel(type: String) =
    ATTRIBUTE_SPECS.firstOrNull { it.type == type }?.labelRes ?: MR.string.profile_edit_link
