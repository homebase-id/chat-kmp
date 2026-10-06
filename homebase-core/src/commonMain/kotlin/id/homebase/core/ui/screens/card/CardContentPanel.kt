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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.profile.ATTRIBUTE_SPECS
import id.homebase.core.ui.screens.profile.AttributeFields
import id.homebase.core.ui.screens.profile.AudienceBadge
import id.homebase.core.ui.screens.profile.AudiencePopover
import id.homebase.core.ui.screens.profile.CookieIcon
import id.homebase.core.ui.screens.profile.EditorActions
import id.homebase.core.ui.screens.profile.PanelHeader
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.ui.screens.profile.ProfileEditViewModel
import id.homebase.core.ui.screens.profile.ProfileField
import id.homebase.core.ui.screens.profile.isAttributeValid
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.resources.MR
import id.homebase.resources.profile_card_content_add_title
import id.homebase.resources.profile_card_content_add_visible
import id.homebase.resources.profile_card_content_empty
import id.homebase.resources.profile_card_content_on_card
import id.homebase.resources.profile_card_content_public_hint
import id.homebase.resources.profile_card_content_switch
import id.homebase.resources.profile_edit_add_group_social
import id.homebase.resources.profile_edit_bio
import id.homebase.resources.profile_edit_email
import id.homebase.resources.profile_edit_link
import id.homebase.resources.profile_edit_link_target
import id.homebase.resources.profile_edit_link_text
import id.homebase.resources.profile_edit_phone
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

internal val CARD_CONTENT_BODY_HEIGHT = 288.dp
internal val CARD_CONTENT_WIDE_BODY_HEIGHT = 440.dp

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
    var popoverFor by remember { mutableStateOf<Uuid?>(null) }
    var adding by remember { mutableStateOf<AddKind?>(null) }
    val present = items.map { it.type }.toSet()
    val missingSocials = CARD_SOCIAL_TYPES.filter { it !in present }
    val addable = AddKind.entries.filter { kind ->
        when (kind) {
            AddKind.Link -> true
            AddKind.Social -> missingSocials.isNotEmpty()
            else -> kind.type !in present
        }
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
        SectionTitle(stringResource(MR.string.profile_card_content_on_card, audienceLabel(card)), busy = !enabled)
        if (items.isEmpty()) {
            EmptyContent(modifier = Modifier.padding(horizontal = SECTION_INSET))
        }
        Column(
            modifier = Modifier.padding(horizontal = SECTION_INSET),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            items.forEachIndexed { index, item ->
                ContentRow(
                    item = item,
                    circles = circles,
                    enabled = enabled,
                    shape = groupedShape(index, items.size),
                    popoverOpen = popoverFor == item.id,
                    onOpenPopover = { popoverFor = item.id },
                    onClosePopover = { popoverFor = null },
                    onToggle = { onToggle(item.id, it) },
                    onAudience = { audience ->
                        popoverFor = null
                        onAudience(item.id, audience)
                    },
                )
            }
        }
        if (addable.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            SectionTitle(stringResource(MR.string.profile_card_content_add_title))
            ScrollableChoiceRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = SECTION_INSET,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                addable.forEach { kind ->
                    FilledTonalButton(
                        onClick = { adding = kind },
                        enabled = enabled,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(addIcon(kind), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(addLabel(kind)), maxLines = 1)
                    }
                }
            }
        }
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

@Composable
private fun SectionTitle(text: String, busy: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = SECTION_INSET + 8.dp, end = SECTION_INSET + 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (busy) LoadingIndicator(modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun EmptyContent(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(GROUP_OUTER_CORNER),
        modifier = modifier.fillMaxWidth(),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun ContentRow(
    item: CardContentItem,
    circles: List<CardCircle>,
    enabled: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    popoverOpen: Boolean,
    onOpenPopover: () -> Unit,
    onClosePopover: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onAudience: (ProfileAudience) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(typeLabel(item.type))
    val switchLabel = stringResource(MR.string.profile_card_content_switch, item.text)
    // A locked row opens its audience as a whole, so the hint inside it has a full-height target.
    val toggle = if (item.locked) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onOpenPopover)
    } else {
        Modifier
            .toggleable(value = item.shown, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .semantics { contentDescription = switchLabel }
    }
    Surface(color = colors.surfaceContainerHigh, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = toggle.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            Row(modifier = Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                ShownBadge(icon = typeIcon(item.type), shown = item.shown)
                Spacer(Modifier.width(BADGE_GAP))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.text,
                        // User content keeps its own direction, so a Latin value in an RTL layout truncates at its own end.
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (item.locked) {
                    // Sits where the switch would, so a locked row still reads as on at a glance.
                    Box(modifier = Modifier.widthIn(min = 52.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                } else {
                    Switch(
                        checked = item.shown,
                        onCheckedChange = null,
                        enabled = enabled,
                        thumbContent = if (item.shown) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                        } else {
                            null
                        },
                    )
                }
            }
            if (item.locked) {
                PublicHint(modifier = Modifier.padding(start = BADGE_SIZE + BADGE_GAP, bottom = 4.dp)) {
                    if (popoverOpen) {
                        AudiencePopover(
                            title = label,
                            icon = typeIcon(item.type),
                            initial = item.audience,
                            circles = circles,
                            otherNames = emptyMap(),
                            onSave = onAudience,
                            onDismiss = onClosePopover,
                        )
                    }
                }
            }
        }
    }
}

// Off is a quiet square, on springs into the filled cookie the profile editor uses for a detail on a card.
@Composable
private fun ShownBadge(icon: ImageVector, shown: Boolean) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val morph = remember { Morph(MaterialShapes.Square, MaterialShapes.Cookie9Sided) }
    val progress by animateFloatAsState(if (shown) 1f else 0f, motion.fastSpatialSpec())
    val fill by animateColorAsState(if (shown) colors.secondary else colors.surfaceContainerHigh, motion.defaultEffectsSpec())
    val tint by animateColorAsState(if (shown) colors.onSecondary else colors.onSurfaceVariant, motion.defaultEffectsSpec())
    val shape = MorphShape(morph, progress)
    Box(
        modifier = Modifier
            .size(BADGE_SIZE)
            .background(fill, shape)
            .border(1.dp, colors.outlineVariant.copy(alpha = 1f - progress), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PublicHint(modifier: Modifier = Modifier, popover: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Public, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(MR.string.profile_card_content_public_hint),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        }
        popover()
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
