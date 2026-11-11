@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalUuidApi::class, ExperimentalLayoutApi::class)

package id.homebase.core.ui.screens.card

import id.homebase.resources.circle_new
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.Dp
import id.homebase.core.widget.connectedButtonShapes
import id.homebase.resources.profile_card_content_hidden_circle
import id.homebase.resources.profile_card_content_hidden_desc
import id.homebase.resources.profile_card_content_hidden_public
import id.homebase.resources.profile_card_content_not_on_card_desc_public
import id.homebase.resources.profile_card_content_on_card_desc_reorder
import id.homebase.resources.profile_card_content_saving
import id.homebase.resources.profile_card_design_action
import id.homebase.resources.profile_card_field_reorder
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.screens.profile.ExistingAvatarContent
import id.homebase.core.ui.screens.profile.PanelHeader
import id.homebase.core.ui.screens.profile.ProfileAudience
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.resources.MR
import id.homebase.resources.cd_profile_avatar_change_photo
import id.homebase.resources.profile_avatar_edit_remove
import id.homebase.resources.profile_card_content_name_required
import id.homebase.resources.profile_card_content_on_card
import id.homebase.resources.profile_card_import_action
import id.homebase.resources.profile_card_import_count
import id.homebase.resources.profile_card_import_hint
import id.homebase.resources.profile_card_import_title
import id.homebase.resources.profile_card_photo_circle_pending
import id.homebase.resources.profile_card_photo_label
import id.homebase.resources.profile_card_photo_own_circle
import id.homebase.resources.profile_edit_photo_inherits_earlier
import id.homebase.resources.profile_edit_photo_inherits_none
import id.homebase.resources.profile_edit_photo_inherits_public
import id.homebase.resources.profile_edit_photo_public_caption
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import id.homebase.resources.profile_card_content_never_on_card_desc
import id.homebase.resources.profile_card_content_not_on_card
import id.homebase.resources.profile_card_content_not_on_card_desc
import id.homebase.resources.profile_card_content_on_card_desc
import id.homebase.resources.profile_card_field_hide
import id.homebase.resources.profile_card_field_move_down
import id.homebase.resources.profile_card_field_move_up
import id.homebase.resources.profile_card_field_show
import id.homebase.resources.profile_card_field_add_title
import id.homebase.resources.profile_card_field_remove
import id.homebase.resources.profile_card_field_to_back
import id.homebase.resources.profile_card_field_to_front
import id.homebase.resources.profile_card_content_front
import id.homebase.resources.profile_card_content_front_desc
import id.homebase.resources.profile_card_content_front_empty
import id.homebase.resources.profile_card_content_back
import id.homebase.resources.profile_card_content_back_desc
import id.homebase.resources.profile_card_content_unavailable
import id.homebase.resources.profile_card_content_unavailable_desc
import id.homebase.resources.profile_card_content_unavailable_names_more
import id.homebase.resources.profile_card_content_unavailable_only
import id.homebase.resources.profile_card_content_unavailable_only_me
import id.homebase.resources.profile_edit_audience_other_circle
import id.homebase.resources.profile_card_content_add_new
import id.homebase.resources.profile_card_content_empty
import id.homebase.core.ui.screens.profile.ATTRIBUTE_SPECS
import id.homebase.core.ui.screens.profile.AddAttributeSheet
import id.homebase.core.ui.screens.profile.AudienceBadge
import id.homebase.resources.cancel
import id.homebase.resources.done
import id.homebase.resources.profile_card_cards_public
import id.homebase.resources.profile_card_content_choose_cards
import id.homebase.resources.profile_edit_audience_only_me_hint
import id.homebase.resources.profile_card_content_edit_this
import id.homebase.resources.profile_card_switcher
import id.homebase.resources.profile_edit_load_failed
import id.homebase.resources.profile_edit_load_failed_desc
import id.homebase.resources.profile_edit_retry
import id.homebase.core.widget.WithTooltip
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.Color

private val PHOTO_SIZE = 80.dp

internal class CardPhotoUi(
    val photo: ProfileAttribute?,
    val own: Boolean,
    val canChange: Boolean,
    val busy: Boolean,
)

@Composable
internal fun CardContentEditor(
    card: CardAudience,
    items: List<CardContentItem>,
    circles: List<CardCircle>,
    photo: CardPhotoUi,
    enabled: Boolean,
    importSources: List<CardImportSource>,
    showCardsButton: Boolean,
    onAudience: (Uuid, ProfileAudience) -> Unit,
    onEdit: (Uuid, Map<String, String>, ProfileAudience) -> Unit,
    onDelete: (Uuid) -> Unit,
    onAdd: (String, Map<String, String>) -> Unit,
    onChangePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onImport: (CardAudience) -> Unit,
    modifier: Modifier = Modifier,
    onFieldShown: (Uuid, Boolean) -> Unit = { _, _ -> },
    onFieldsReordered: (List<Uuid>) -> Unit = {},
    busy: Boolean = false,
    onDesign: (() -> Unit)? = null,
    edge: Dp = 16.dp,
) {
    var editingId by remember { mutableStateOf<Uuid?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var choosingMore by remember { mutableStateOf(false) }
    var choosingKind by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    var pendingId by remember { mutableStateOf<Uuid?>(null) }
    LaunchedEffect(enabled) { if (enabled) pendingId = null }
    // A circle's card splits into front, back and not available; with cards off this is the profile editor, so it keeps every field.
    val circleCard = card is CardAudience.Circle && showCardsButton
    val onCard = items.filter { it.onCard }
    val back = if (circleCard) items.filter { it.shown && !it.onCard } else emptyList()
    val unavailable = if (circleCard) items.filterNot { it.shown } else emptyList()
    val frontSlot = onCard.isNotEmpty()
    val backSlot = back.isNotEmpty()
    val notOnCard = if (circleCard) emptyList() else items.filter { it.shown && it.renderable && it.hidden }
    val unrenderable = if (circleCard) emptyList() else items.filter { it.shown && !it.renderable }
    val couldAdd = if (circleCard) emptyList() else items.filterNot { it.shown }
    val present = items.addableTypesPresent()
    val missingSocials = CARD_SOCIAL_TYPES.filter { it !in present }
    val missingMore = ATTRIBUTE_SPECS.filter { it.type in MORE_TYPES && it.type !in present }
    val linkGroups = listOf(onCard.filter { it.type == ProfileAttributeTypes.LINK }, onCard.filter { it.type in CARD_SOCIAL_TYPES })
    val canReorder = linkGroups.any { it.size > 1 }
    val isAvailable: (AddKind) -> Boolean = { kind ->
        when (kind) {
            AddKind.Social -> missingSocials.isNotEmpty()
            AddKind.More -> missingMore.isNotEmpty()
            else -> kind.type in REPEATABLE_TYPES || kind.type !in present
        }
    }
    val startAdding: (AddKind) -> Unit = { kind ->
        when (kind) {
            AddKind.More -> choosingMore = true
            AddKind.Social -> adding = missingSocials.firstOrNull()
            else -> adding = kind.type
        }
    }
    val addAction: @Composable () -> Unit = {
        if (!circleCard) {
            AddGroup(isAvailable = isAvailable, enabled = enabled, onAdd = startAdding)
        } else if (AddKind.entries.any(isAvailable)) {
            FilledTonalButton(
                onClick = { choosingKind = true },
                enabled = enabled,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag("content_add"),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(MR.string.profile_card_content_add_new), maxLines = 1)
            }
        }
    }
    val removeFromCard: (CardContentItem) -> Unit = { item ->
        item.audience.shownOn(card, false, null, circles)?.let { onAudience(item.id, it) }
        if (item.hidden) onFieldShown(item.id, true)
    }
    val place: (CardContentItem, Boolean) -> Unit = { item, front ->
        onFieldShown(item.id, front)
        item.audience.shownOn(card, true, null)?.let { onAudience(item.id, it) }
    }
    val row: @Composable (CardContentItem, Int, Int, FieldDisplay?) -> Unit = { item, index, count, display ->
        key(item.id) { EditorRow(
            item = item,
            circles = circles,
            index = index,
            count = count,
            enabled = enabled,
            pending = !enabled && pendingId == item.id,
            showCardsButton = showCardsButton,
            display = display,
            onApply = {
                pendingId = item.id
                onAudience(item.id, it)
            },
            onOpen = { editingId = item.id },
            addable = display == null && !item.shown,
            addAudience = item.audience.shownOn(card, true, null).takeIf { card is CardAudience.Circle },
        ) }
    }
    val frontRow: @Composable (CardContentItem, Int, Int, FieldDisplay) -> Unit = { item, index, count, display ->
        if (!circleCard) {
            row(item, index, count, display)
        } else {
            key(item.id) {
                CardFieldRow(
                    item = item,
                    index = index,
                    count = count,
                    enabled = enabled,
                    tag = "field_front",
                    onOpen = { editingId = item.id },
                    onRemove = { removeFromCard(item) },
                    onMove = display.onToggle,
                    display = display,
                    handleSlot = canReorder,
                    removeSlot = frontSlot,
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = SECTION_INSET)
            .padding(top = edge, bottom = 16.dp),
    ) {
        AudienceHeading(card, emoji = circles.emojiOf(card))
        AnimatedVisibility(
            visible = busy,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) { SavingLine(Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(16.dp))
        PhotoRow(card = card, photo = photo, onChange = onChangePhoto, onRemove = onRemovePhoto)
        if (importSources.isNotEmpty() || onDesign != null) {
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (importSources.isNotEmpty()) {
                    OutlinedButton(onClick = { copying = true }, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(MR.string.profile_card_import_action), maxLines = 1)
                    }
                }
                if (onDesign != null) {
                    OutlinedButton(onClick = onDesign, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Outlined.Palette, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(MR.string.profile_card_design_action), maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(SECTION_GAP))
        SectionLabel(
            stringResource(if (circleCard) MR.string.profile_card_content_front else MR.string.profile_card_content_on_card),
            when {
                circleCard -> stringResource(MR.string.profile_card_content_front_desc)
                onCard.isEmpty() -> null
                canReorder -> stringResource(MR.string.profile_card_content_on_card_desc_reorder)
                else -> stringResource(MR.string.profile_card_content_on_card_desc)
            },
        )
        if (onCard.isEmpty() && circleCard) {
            Text(
                text = stringResource(if (back.any { it.renderable }) MR.string.profile_card_content_front_empty else MR.string.profile_card_content_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else if (onCard.isEmpty()) {
            EmptyContent { addAction() }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                val fixed = onCard.filterNot { it.orderable }
                val total = onCard.size
                fixed.forEachIndexed { index, item ->
                    frontRow(item, index, total, FieldDisplay(onCard = true, onToggle = { onFieldShown(item.id, false) }))
                }
                var offset = fixed.size
                linkGroups.forEach { group ->
                    if (group.isNotEmpty()) {
                        val start = offset
                        ReorderableRows(items = group, gap = ROW_GAP, onReorder = onFieldsReordered) { item, index, count, move, handle, lifted ->
                            frontRow(
                                item, start + index, total,
                                FieldDisplay(
                                    onCard = true,
                                    onToggle = { onFieldShown(item.id, false) },
                                    moveUp = { move(-1) }.takeIf { count > 1 && index > 0 },
                                    moveDown = { move(1) }.takeIf { count > 1 && index < count - 1 },
                                    handle = handle.takeIf { count > 1 },
                                    lifted = lifted,
                                ),
                            )
                        }
                        offset += group.size
                    }
                }
            }
        }
        if (back.isNotEmpty()) {
            Spacer(Modifier.height(SECTION_GAP))
            SectionLabel(stringResource(MR.string.profile_card_content_back), stringResource(MR.string.profile_card_content_back_desc))
            Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                back.forEachIndexed { index, item ->
                    key(item.id) {
                        CardFieldRow(
                            item = item,
                            index = index,
                            count = back.size,
                            enabled = enabled,
                            tag = "field_back",
                            onOpen = { editingId = item.id },
                            onRemove = { removeFromCard(item) },
                            onMove = { onFieldShown(item.id, true) }.takeIf { item.renderable },
                            removeSlot = backSlot,
                        )
                    }
                }
            }
        }
        if (circleCard) {
            Spacer(Modifier.height(12.dp))
            addAction()
        }
        if (unavailable.isNotEmpty()) {
            Spacer(Modifier.height(SECTION_GAP))
            SectionLabel(stringResource(MR.string.profile_card_content_unavailable), stringResource(MR.string.profile_card_content_unavailable_desc))
            Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                unavailable.forEachIndexed { index, item ->
                    key(item.id) {
                        CardFieldRow(
                            item = item,
                            index = index,
                            count = unavailable.size,
                            enabled = enabled,
                            tag = "field_unavailable",
                            onOpen = { editingId = item.id },
                            onAdd = { place(item, item.renderable) },
                            supporting = unavailableReason(item.audience, circles),
                            muted = true,
                        )
                    }
                }
            }
        }
        if (notOnCard.isNotEmpty()) {
            Spacer(Modifier.height(SECTION_GAP))
            SectionLabel(
                stringResource(MR.string.profile_card_content_not_on_card),
                stringResource(if (card is CardAudience.Circle) MR.string.profile_card_content_not_on_card_desc else MR.string.profile_card_content_not_on_card_desc_public),
            )
            Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                notOnCard.forEachIndexed { index, item ->
                    row(item, index, notOnCard.size, FieldDisplay(onCard = false, onToggle = { onFieldShown(item.id, true) }))
                }
            }
        }
        if (unrenderable.isNotEmpty()) {
            Spacer(Modifier.height(if (notOnCard.isEmpty()) SECTION_GAP else 12.dp))
            CollapsedGroup(
                title = unrenderable.map { stringResource(typeLabel(it.type)) }.distinct().joinToString(),
                description = stringResource(MR.string.profile_card_content_never_on_card_desc),
                icon = Icons.Outlined.Block,
                tag = "never_on_card",
            ) {
                unrenderable.forEachIndexed { index, item -> row(item, index + 1, unrenderable.size + 1, null) }
            }
        }
        if (couldAdd.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CollapsedGroup(
                title = stringResource(
                    if (card is CardAudience.Circle) MR.string.profile_card_content_hidden_circle else MR.string.profile_card_content_hidden_public,
                    couldAdd.size,
                ),
                description = stringResource(MR.string.profile_card_content_hidden_desc),
                icon = Icons.Outlined.VisibilityOff,
                tag = "not_visible",
            ) {
                couldAdd.forEachIndexed { index, item -> row(item, index + 1, couldAdd.size + 1, null) }
            }
        }
        if (onCard.isNotEmpty() && !circleCard) {
            Spacer(Modifier.height(24.dp))
            addAction()
        }
    }

    items.firstOrNull { it.id == editingId }?.let { item ->
        ContentSheet(
            type = item.type,
            card = card,
            circles = circles,
            editing = item,
            onEdit = { updates, audience ->
                pendingId = item.id
                onEdit(item.id, updates, audience)
            },
            onDelete = { id ->
                pendingId = item.id
                onDelete(id)
            },
            onDismiss = { editingId = null },
        )
    }
    if (choosingKind) {
        AddSheet(isAvailable = isAvailable, onAdd = startAdding, onDismiss = { choosingKind = false })
    }
    adding?.let { type ->
        ContentSheet(
            type = type,
            card = card,
            circles = circles,
            socials = if (type in CARD_SOCIAL_TYPES) missingSocials else emptyList(),
            onAdd = { t, updates ->
                pendingId = null
                onAdd(t, updates)
            },
            onDismiss = { adding = null },
        )
    }
    if (choosingMore) {
        AddAttributeSheet(
            missing = missingMore,
            onPick = {
                choosingMore = false
                adding = it.type
            },
            onDismiss = { choosingMore = false },
        )
    }
    if (copying) {
        CopyFromSheet(
            sources = importSources,
            onPick = {
                pendingId = null
                onImport(it)
            },
            onDismiss = { copying = false },
        )
    }
}

private val SWITCHER_LABEL_MAX = 160.dp
private val SECTION_GAP = 24.dp

@Composable
private fun SavingLine(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LoadingIndicator(modifier = Modifier.size(24.dp))
        Text(
            text = stringResource(MR.string.profile_card_content_saving),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// One summary row standing for a whole group the card doesn't show; opening it lists the rows so each stays editable.
@Composable
private fun CollapsedGroup(
    title: String,
    description: String,
    icon: ImageVector,
    tag: String,
    rows: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val turn by animateFloatAsState(if (open) 180f else 0f, motion.defaultSpatialSpec())
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP), modifier = Modifier.testTag(tag)) {
        Surface(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceVariant,
            shape = groupedShape(0, if (open) 2 else 1),
            modifier = Modifier.fillMaxWidth().testTag("${tag}_toggle").toggleable(value = open, role = Role.Button, onValueChange = { open = it }),
        ) {
            Row(
                modifier = Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BADGE_GAP),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Text(description, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = turn })
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) { rows() }
        }
    }
}

@Composable
private fun SectionLabel(text: String, description: String? = null) {
    Column(modifier = Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        if (description != null) {
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhotoRow(card: CardAudience, photo: CardPhotoUi, onChange: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val change = stringResource(MR.string.cd_profile_avatar_change_photo)
    val status = when {
        photo.own && card is CardAudience.Circle -> stringResource(MR.string.profile_card_photo_own_circle)
        photo.own -> stringResource(MR.string.profile_edit_photo_public_caption)
        photo.photo == null -> stringResource(MR.string.profile_edit_photo_inherits_none)
        photo.photo.visibility == ProfileVisibility.ANONYMOUS -> stringResource(MR.string.profile_edit_photo_inherits_public)
        else -> stringResource(MR.string.profile_edit_photo_inherits_earlier)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier
                    .size(PHOTO_SIZE)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(colors.surfaceContainerHighest)
                    .then(if (photo.canChange) Modifier.clickable(role = Role.Button, onClickLabel = change, onClick = onChange) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                ExistingAvatarContent(photo.photo, "CardContentEditor")
                if (photo.busy) LoadingIndicator(modifier = Modifier.size(32.dp))
            }
            if (photo.canChange) {
                FilledIconButton(onClick = onChange, modifier = Modifier.size(28.dp).offset(x = 4.dp, y = 4.dp)) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = change, modifier = Modifier.size(16.dp))
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(MR.string.profile_card_photo_label),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Text(text = status, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            when {
                photo.own && photo.canChange -> TextButton(onClick = onRemove, enabled = !photo.busy) {
                    Text(stringResource(MR.string.profile_avatar_edit_remove))
                }
                !photo.canChange && card is CardAudience.Circle -> Text(
                    text = stringResource(MR.string.profile_card_photo_circle_pending),
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

// Tapping a row edits the item everywhere; its leading minus or plus only takes it off, or puts it on, this card.
@Composable
private fun EditorRow(
    item: CardContentItem,
    circles: List<CardCircle>,
    index: Int,
    count: Int,
    enabled: Boolean,
    pending: Boolean,
    showCardsButton: Boolean,
    onApply: (ProfileAudience) -> Unit,
    onOpen: () -> Unit,
    display: FieldDisplay? = null,
    addable: Boolean = false,
    addAudience: ProfileAudience? = null,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val label = stringResource(typeLabel(item.type))
    val moveUpLabel = stringResource(MR.string.profile_card_field_move_up, label)
    val moveDownLabel = stringResource(MR.string.profile_card_field_move_down, label)
    val toggleLabel = stringResource(if (display?.onCard == false) MR.string.profile_card_field_show else MR.string.profile_card_field_hide, label)
    val addLabel = stringResource(MR.string.profile_card_field_add_title, label)
    val prose = item.type == ProfileAttributeTypes.NAME || item.type == ProfileAttributeTypes.BIO_SUMMARY
    val chooseCards = stringResource(MR.string.profile_card_content_choose_cards, item.text)
    val edit = stringResource(MR.string.profile_card_content_edit_this, label)
    val off = display?.onCard == false
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    var choosing by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (display?.lifted == true) 1f else 0f, motion.fastSpatialSpec())
    val container by animateColorAsState(if (off) colors.surfaceContainer else colors.surfaceContainerHigh, motion.defaultEffectsSpec())
    Surface(
        onClick = onOpen,
        enabled = enabled,
        color = container,
        shape = groupedShape(index, count, lift),
        shadowElevation = LIFT_ELEVATION * lift,
        modifier = Modifier.fillMaxWidth()
            .onPreviewKeyEvent { display.keyMove(it)?.invoke() != null }
            .semantics {
                onClick(label = edit) { onOpen(); true }
                val actions = display.moveActions(moveUpLabel, moveDownLabel) + listOfNotNull(
                    display?.takeUnless { item.required }?.let { d -> CustomAccessibilityAction(toggleLabel) { d.onToggle(); true } },
                )
                if (actions.isNotEmpty()) customActions = actions
            },
    ) {
        val audience: @Composable () -> Unit = {
            if (showCardsButton && !pending) {
                Surface(
                    onClick = { choosing = true },
                    enabled = enabled,
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier.testTag("cards_button").semantics { contentDescription = chooseCards },
                ) {
                    Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.CenterStart) {
                        AudienceBadge(audience = item.audience, circles = circles)
                    }
                }
            } else {
                AudienceBadge(audience = item.audience, circles = circles, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        val text: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier = modifier) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    audience()
                }
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr),
                    color = if (off) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = if (prose) 2 else 1,
                    softWrap = prose,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.required) {
                    Text(
                        text = stringResource(MR.string.profile_card_content_name_required),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        val toggle: @Composable () -> Unit = {
            if (display != null && !item.required && !pending) {
                val on = display.onCard
                IconButton(
                    onClick = display.onToggle,
                    enabled = enabled,
                    modifier = Modifier.testTag(if (on) "field_remove" else "field_add"),
                ) {
                    Icon(
                        imageVector = if (on) Icons.Outlined.RemoveCircleOutline else Icons.Outlined.AddCircleOutline,
                        contentDescription = toggleLabel,
                        tint = if (on) colors.onSurfaceVariant else colors.primary,
                    )
                }
            } else if (addable && !pending) {
                IconButton(
                    onClick = { addAudience?.let(onApply) ?: run { choosing = true } },
                    enabled = enabled,
                    modifier = Modifier.testTag("field_add"),
                ) {
                    Icon(Icons.Outlined.AddCircleOutline, contentDescription = addLabel, tint = colors.primary)
                }
            }
        }
        val controls: @Composable () -> Unit = {
            if (display != null) ReorderHandle(display, enabled, label)
            when {
                pending -> LoadingIndicator(modifier = Modifier.size(40.dp).padding(8.dp))
                showCardsButton -> Unit
                item.required || item.locked -> Icon(
                    Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(40.dp).padding(8.dp),
                )
            }
        }
        if (stacked) {
            // At large text the value needs the full width; the switch drops to its own line rather than squeezing it.
            Column(modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    toggle()
                    ShownBadge(icon = typeIcon(item.type), shown = display?.onCard ?: item.shown)
                    Spacer(Modifier.width(BADGE_GAP))
                    text(Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { controls() }
            }
        } else {
            Row(
                modifier = Modifier.heightIn(min = 64.dp).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                toggle()
                ShownBadge(icon = typeIcon(item.type), shown = display?.onCard ?: item.shown)
                Spacer(Modifier.width(BADGE_GAP))
                text(Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                controls()
            }
        }
    }
    if (choosing) {
        CardsSheet(item = item, circles = circles, onApply = onApply, onDismiss = { choosing = false })
    }
}

// Names the circles that can still see the field, so the owner knows what to change to bring it to this card.
@Composable
private fun unavailableReason(audience: ProfileAudience, circles: List<CardCircle>): String {
    val other = stringResource(MR.string.profile_edit_audience_other_circle)
    val circleAudience = audience as? ProfileAudience.Circles
    val names = circleAudience?.let { circles.filter { c -> c.id in it.ids }.map { c -> c.name } + it.otherIds.map { other } }.orEmpty()
    val listed = when {
        names.size > UNAVAILABLE_NAMES -> stringResource(MR.string.profile_card_content_unavailable_names_more, names.first(), names.size - 1)
        else -> names.joinToString(", ")
    }
    return if (names.isEmpty()) stringResource(MR.string.profile_card_content_unavailable_only_me) else stringResource(MR.string.profile_card_content_unavailable_only, listed)
}

private const val UNAVAILABLE_NAMES = 2

// Rows in a section share one leading slot so values line up; a section with no remove buttons has none.
@Composable
private fun CardFieldRow(
    item: CardContentItem,
    index: Int,
    count: Int,
    enabled: Boolean,
    tag: String,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    supporting: String? = null,
    display: FieldDisplay? = null,
    handleSlot: Boolean = false,
    muted: Boolean = false,
    removeSlot: Boolean = onRemove != null || onAdd != null,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(typeLabel(item.type))
    val moveUpLabel = stringResource(MR.string.profile_card_field_move_up, label)
    val moveDownLabel = stringResource(MR.string.profile_card_field_move_down, label)
    val edit = stringResource(MR.string.profile_card_content_edit_this, label)
    val remove = stringResource(MR.string.profile_card_field_remove, label)
    val add = stringResource(MR.string.profile_card_field_add_title, label)
    val toBack = stringResource(MR.string.profile_card_field_to_back, label)
    val toFront = stringResource(MR.string.profile_card_field_to_front, label)
    val prose = item.type == ProfileAttributeTypes.NAME || item.type == ProfileAttributeTypes.BIO_SUMMARY
    val trailing = onMove != null || handleSlot || display?.handle != null
    val lift by animateFloatAsState(if (display?.lifted == true) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec())
    Surface(
        color = if (muted) colors.surfaceContainer else colors.surfaceContainerHigh,
        shape = groupedShape(index, count, lift),
        shadowElevation = LIFT_ELEVATION * lift,
        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { display.keyMove(it)?.invoke() != null },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = edit, onClick = onOpen)
                .testTag(tag)
                .semantics {
                    val actions = display.moveActions(moveUpLabel, moveDownLabel)
                    if (actions.isNotEmpty()) customActions = actions
                }
                .heightIn(min = if (supporting != null) THREE_LINE_HEIGHT else TWO_LINE_HEIGHT)
                .padding(start = if (removeSlot) ROW_SLOT_INSET else ROW_TEXT_INSET, end = if (trailing) ROW_SLOT_INSET else ROW_TEXT_INSET)
                .padding(vertical = if (supporting != null) 12.dp else 8.dp),
        ) {
            if (removeSlot) {
                Box(Modifier.size(ROW_SLOT), contentAlignment = Alignment.Center) {
                    if (onRemove != null) {
                        IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.testTag("field_remove")) {
                            Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = remove, tint = colors.onSurfaceVariant)
                        }
                    } else if (onAdd != null) {
                        IconButton(onClick = onAdd, enabled = enabled, modifier = Modifier.testTag("field_add")) {
                            Icon(Icons.Outlined.AddCircleOutline, contentDescription = add, tint = colors.primary)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = if (removeSlot) ROW_SLOT_INSET else 0.dp, end = 8.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr),
                    color = if (muted) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = if (prose) 2 else 1,
                    softWrap = prose,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting != null) {
                    Text(text = supporting, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (onMove != null) {
                val onFront = display?.onCard == true
                FilledTonalIconButton(
                    onClick = onMove,
                    enabled = enabled,
                    modifier = Modifier.testTag(if (onFront) "field_to_back" else "field_to_front"),
                ) {
                    Icon(
                        imageVector = if (onFront) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                        contentDescription = if (onFront) toBack else toFront,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            when {
                display?.handle != null -> ReorderHandle(display, enabled, label)
                handleSlot -> Spacer(Modifier.size(ROW_SLOT))
            }
        }
    }
}

// Its own clickable takes the tap, so tapping the handle never also taps the row under it.
@Composable
private fun ReorderHandle(display: FieldDisplay, enabled: Boolean, label: String) {
    val handle = display.handle ?: return
    val colors = MaterialTheme.colorScheme
    val moveUpLabel = stringResource(MR.string.profile_card_field_move_up, label)
    val moveDownLabel = stringResource(MR.string.profile_card_field_move_down, label)
    val reorderLabel = stringResource(MR.string.profile_card_field_reorder, label)
    var menu by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .testTag("field_handle")
            .then(handle)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = reorderLabel) { menu = true }
            .semantics { contentDescription = reorderLabel },
    ) {
        Icon(Icons.Rounded.DragHandle, contentDescription = null, tint = colors.onSurfaceVariant)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            display.moveUp?.let { up ->
                DropdownMenuItem(
                    text = { Text(moveUpLabel) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                    onClick = { menu = false; up() },
                )
            }
            display.moveDown?.let { down ->
                DropdownMenuItem(
                    text = { Text(moveDownLabel) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                    onClick = { menu = false; down() },
                )
            }
        }
    }
}

private fun FieldDisplay?.keyMove(event: KeyEvent): (() -> Unit)? =
    if (this == null || event.type != KeyEventType.KeyDown || !event.isAltPressed) {
        null
    } else {
        when (event.key) {
            Key.DirectionUp -> moveUp
            Key.DirectionDown -> moveDown
            else -> null
        }
    }

private fun FieldDisplay?.moveActions(upLabel: String, downLabel: String): List<CustomAccessibilityAction> = listOfNotNull(
    this?.moveUp?.let { up -> CustomAccessibilityAction(upLabel) { up(); true } },
    this?.moveDown?.let { down -> CustomAccessibilityAction(downLabel) { down(); true } },
)

private val LIFT_ELEVATION = 8.dp
private val ROW_SLOT = 48.dp
// A 48dp slot 4dp in puts its 24dp icon on the M3 list's 16dp keyline and the text on the 56dp one.
private val ROW_SLOT_INSET = 4.dp
private val ROW_TEXT_INSET = 16.dp
private val TWO_LINE_HEIGHT = 72.dp
private val THREE_LINE_HEIGHT = 88.dp
private const val STACKED_FONT_SCALE = 1.5f

internal fun cardsDraftAudience(public: Boolean, ticked: Set<String>, otherIds: Set<String>): ProfileAudience = when {
    public -> ProfileAudience.Public
    ticked.isNotEmpty() || otherIds.isNotEmpty() -> ProfileAudience.Circles(ticked, otherIds)
    else -> ProfileAudience.OnlyMe
}

@Composable
internal fun CardsSheet(
    item: CardContentItem,
    circles: List<CardCircle>,
    onApply: (ProfileAudience) -> Unit,
    onDismiss: () -> Unit,
) {
    val allIds = circles.map { it.id }.toSet()
    val stored = item.audience
    val otherIds = (stored as? ProfileAudience.Circles)?.otherIds.orEmpty()
    var public by remember { mutableStateOf(stored == ProfileAudience.Public) }
    val initialTicked = remember {
        when (stored) {
            ProfileAudience.Public -> allIds
            ProfileAudience.OnlyMe -> emptySet()
            is ProfileAudience.Circles -> stored.ids.filter { it in allIds }.toSet()
        }
    }
    var ticked by remember { mutableStateOf(initialTicked) }
    var beforePublic by remember { mutableStateOf(emptySet<String>()) }
    val untouched = public == (stored == ProfileAudience.Public) && ticked == initialTicked
    val draft = if (untouched) stored else cardsDraftAudience(public, ticked, otherIds)
    AdaptiveSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PanelHeader(
                icon = Icons.Outlined.Style,
                title = stringResource(MR.string.profile_card_content_choose_cards, item.text),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            CardsSheetRow(
                label = stringResource(MR.string.profile_card_cards_public),
                checked = public,
                enabled = !item.required,
                tag = "cards_sheet_public",
                onClick = {
                    if (public) {
                        ticked = beforePublic.ifEmpty { allIds }
                        public = false
                    } else {
                        beforePublic = ticked
                        ticked = allIds
                        public = true
                    }
                },
            )
            circles.forEach { circle ->
                CardsSheetRow(
                    label = circle.name,
                    checked = circle.id in ticked,
                    enabled = !public,
                    tag = "cards_sheet_circle_${circle.id}",
                    onClick = { ticked = if (circle.id in ticked) ticked - circle.id else ticked + circle.id },
                )
            }
            if (draft == ProfileAudience.OnlyMe) {
                Text(
                    text = stringResource(MR.string.profile_edit_audience_only_me_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { dismiss() }, modifier = Modifier.testTag("cards_sheet_cancel")) {
                    Text(stringResource(MR.string.cancel))
                }
                TextButton(
                    onClick = { dismiss { onDismiss(); if (draft != stored) onApply(draft) } },
                    modifier = Modifier.testTag("cards_sheet_done"),
                ) { Text(stringResource(MR.string.done)) }
            }
        }
    }
}

@Composable
private fun CardsSheetRow(label: String, checked: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CopyFromSheet(sources: List<CardImportSource>, onPick: (CardAudience) -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PanelHeader(
                icon = Icons.Outlined.ContentCopy,
                title = stringResource(MR.string.profile_card_import_title),
                subtitle = stringResource(MR.string.profile_card_import_hint),
            )
            sources.forEach { source ->
                val name = audienceLabel(source.audience)
                val count = pluralStringResource(MR.plurals.profile_card_import_count, source.newItems, source.newItems)
                Surface(
                    onClick = { dismiss { onDismiss(); onPick(source.audience) } },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$name, $count" },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CookieBadge(
                            icon = audienceIcon(source.audience),
                            container = audienceContainer(source.audience),
                            content = onAudienceContainer(source.audience),
                            size = 40.dp,
                            iconSize = 20.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CardSwitcher(
    cards: List<CardAudience>,
    selected: CardAudience,
    circles: List<CardCircle>,
    onSelect: (CardAudience) -> Unit,
    modifier: Modifier = Modifier,
    onNewCircle: (() -> Unit)? = null,
) {
    val label = stringResource(MR.string.profile_card_switcher)
    val scroll = rememberScrollState()
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fadingEdges(scroll)
                .horizontalScroll(scroll)
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = label }
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            cards.forEachIndexed { index, card ->
                val checked = card.isSameAs(selected)
                val reveal = remember { BringIntoViewRequester() }
                // A long circle name can sit past the edge; the card being edited is always scrolled into sight.
                LaunchedEffect(checked, scroll.maxValue) { if (checked) reveal.bringIntoView() }
                ToggleButton(
                    checked = checked,
                    onCheckedChange = { onSelect(card) },
                    shapes = connectedButtonShapes(index, cards.size),
                    modifier = Modifier.bringIntoViewRequester(reveal).semantics { role = Role.Tab },
                ) {
                    AudienceGlyph(audienceIcon(card), circles.emojiOf(card), ButtonDefaults.IconSize)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(
                        audienceLabel(card),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = SWITCHER_LABEL_MAX),
                    )
                }
            }
        }
        // Outside the scroll, so the way to add a circle stays in reach however many cards there are.
        if (onNewCircle != null) {
            val newCircle = stringResource(MR.string.circle_new)
            WithTooltip(newCircle, modifier = Modifier.padding(end = 12.dp)) {
                FilledTonalIconButton(onClick = onNewCircle) {
                    Icon(Icons.Outlined.Add, contentDescription = newCircle)
                }
            }
        }
    }
}

@Composable
internal fun ContentLoading(failed: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!failed) {
            LoadingIndicator()
            return@Column
        }
        CookieBadge(
            icon = Icons.Outlined.CloudOff,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            size = 72.dp,
            iconSize = 32.dp,
        )
        Text(
            text = stringResource(MR.string.profile_edit_load_failed),
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(MR.string.profile_edit_load_failed_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(MR.string.profile_edit_retry))
        }
    }
}
