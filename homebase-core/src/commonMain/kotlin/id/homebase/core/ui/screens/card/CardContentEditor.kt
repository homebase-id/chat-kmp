@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalUuidApi::class, ExperimentalLayoutApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.semantics.stateDescription
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
import id.homebase.resources.profile_card_change_design
import id.homebase.resources.profile_card_content_could_add
import id.homebase.resources.profile_card_content_name_required
import id.homebase.resources.profile_card_content_required_hint
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
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.semantics.onClick
import id.homebase.core.ui.screens.profile.ATTRIBUTE_SPECS
import id.homebase.core.ui.screens.profile.AddAttributeSheet
import id.homebase.core.ui.screens.profile.AudienceBadge
import id.homebase.resources.profile_card_content_every_card
import id.homebase.resources.profile_card_content_every_card_desc
import id.homebase.resources.profile_card_content_hide_this
import id.homebase.resources.profile_card_content_show_this
import id.homebase.resources.profile_card_content_edit_this
import id.homebase.resources.profile_card_content_every_card_circle
import id.homebase.resources.profile_card_switcher
import id.homebase.resources.profile_edit_load_failed
import id.homebase.resources.profile_edit_load_failed_desc
import id.homebase.resources.profile_edit_retry

private val PHOTO_SIZE = 88.dp

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
    onToggle: (Uuid, Boolean) -> Unit,
    onEdit: (Uuid, Map<String, String>, ProfileAudience) -> Unit,
    onDelete: (Uuid) -> Unit,
    onAdd: (String, Map<String, String>) -> Unit,
    onChangePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onImport: (CardAudience) -> Unit,
    onChangeDesign: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var editingId by remember { mutableStateOf<Uuid?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var choosingMore by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    var pendingId by remember { mutableStateOf<Uuid?>(null) }
    LaunchedEffect(enabled) { if (enabled) pendingId = null }
    val everyCard = items.filter { it.everyCard }
    val onlyHere = items.filter { it.shown && !it.everyCard }
    val couldAdd = items.filterNot { it.shown || it.everyCard }
    val present = items.map { it.type }.toSet()
    val missingSocials = CARD_SOCIAL_TYPES.filter { it !in present }
    val missingMore = ATTRIBUTE_SPECS.filter { it.type in MORE_TYPES && it.type !in present }
    val row: @Composable (CardContentItem, Int, Int) -> Unit = { item, index, count ->
        EditorRow(
            item = item,
            circles = circles,
            shape = groupedShape(index, count),
            enabled = enabled,
            pending = !enabled && pendingId == item.id,
            onToggle = {
                pendingId = item.id
                onToggle(item.id, !item.shown)
            },
            onOpen = { editingId = item.id },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = SECTION_INSET).padding(bottom = 8.dp),
    ) {
        AudienceHeading(card)
        Spacer(Modifier.height(16.dp))
        PhotoRow(card = card, photo = photo, onChange = onChangePhoto, onRemove = onRemovePhoto)
        if (onChangeDesign != null || importSources.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onChangeDesign != null) {
                    FilledTonalButton(onClick = onChangeDesign, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Outlined.Palette, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(MR.string.profile_card_change_design), maxLines = 1)
                    }
                }
                if (importSources.isNotEmpty()) {
                    OutlinedButton(onClick = { copying = true }, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(MR.string.profile_card_import_action), maxLines = 1)
                    }
                }
            }
        }
        if (everyCard.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(
                stringResource(MR.string.profile_card_content_every_card),
                stringResource(if (card is CardAudience.Circle) MR.string.profile_card_content_every_card_circle else MR.string.profile_card_content_every_card_desc),
            )
            Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                everyCard.forEachIndexed { index, item -> row(item, index, everyCard.size) }
            }
        }
        if (card is CardAudience.Circle) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(MR.string.profile_card_content_on_card))
            Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                if (onlyHere.isEmpty()) EmptyContent()
                onlyHere.forEachIndexed { index, item -> row(item, index, onlyHere.size) }
            }
        }
        if (couldAdd.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(MR.string.profile_card_content_could_add))
            Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                couldAdd.forEachIndexed { index, item -> row(item, index, couldAdd.size) }
            }
        }
        Spacer(Modifier.height(24.dp))
        AddGroup(
            isAvailable = { kind ->
                when (kind) {
                    AddKind.Social -> missingSocials.isNotEmpty()
                    AddKind.More -> missingMore.isNotEmpty()
                    else -> kind.type in REPEATABLE_TYPES || kind.type !in present
                }
            },
            enabled = enabled,
            onAdd = { kind ->
                when (kind) {
                    AddKind.More -> choosingMore = true
                    AddKind.Social -> adding = missingSocials.firstOrNull()
                    else -> adding = kind.type
                }
            },
        )
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
    adding?.let { type ->
        ContentSheet(
            type = type,
            card = card,
            circles = circles,
            socials = if (type in CARD_SOCIAL_TYPES) missingSocials else emptyList(),
            onAdd = onAdd,
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
        CopyFromSheet(sources = importSources, onPick = onImport, onDismiss = { copying = false })
    }
}

@Composable
private fun SectionLabel(text: String, description: String? = null) {
    Column(modifier = Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                FilledIconButton(onClick = onChange, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = change, modifier = Modifier.size(18.dp))
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

// Tapping a row edits the item everywhere; its trailing control only hides it from, or shows it on, this card.
@Composable
private fun EditorRow(
    item: CardContentItem,
    circles: List<CardCircle>,
    shape: Shape,
    enabled: Boolean,
    pending: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(typeLabel(item.type))
    val prose = item.type == ProfileAttributeTypes.NAME || item.type == ProfileAttributeTypes.BIO_SUMMARY
    val hide = stringResource(MR.string.profile_card_content_hide_this, item.text)
    val show = stringResource(MR.string.profile_card_content_show_this, item.text)
    val edit = stringResource(MR.string.profile_card_content_edit_this, label)
    val required = stringResource(MR.string.profile_card_content_name_required)
    Surface(
        onClick = onOpen,
        enabled = enabled,
        color = colors.surfaceContainerHigh,
        shape = shape,
        modifier = Modifier.fillMaxWidth().semantics {
            onClick(label = edit) { onOpen(); true }
            if (item.required) stateDescription = required
        },
    ) {
        Row(
            modifier = Modifier.heightIn(min = 64.dp).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShownBadge(icon = typeIcon(item.type), shown = item.shown)
            Spacer(Modifier.width(BADGE_GAP))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    color = colors.onSurface,
                    maxLines = if (prose) 2 else 1,
                    softWrap = prose,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.required) {
                    Text(
                        text = stringResource(MR.string.profile_card_content_required_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                // The section already says who sees an item on every card.
                if (!item.everyCard) {
                    Spacer(Modifier.height(2.dp))
                    AudienceBadge(audience = item.audience, circles = circles)
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                pending -> LoadingIndicator(modifier = Modifier.size(40.dp).padding(8.dp))
                item.required || item.locked -> Icon(
                    Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(40.dp).padding(8.dp),
                )
                item.shown -> IconButton(onClick = onToggle, enabled = enabled) {
                    Icon(Icons.Outlined.VisibilityOff, contentDescription = hide)
                }
                else -> FilledTonalIconButton(onClick = onToggle, enabled = enabled) {
                    Icon(Icons.Filled.Add, contentDescription = show)
                }
            }
        }
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
    onSelect: (CardAudience) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(MR.string.profile_card_switcher)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = label }
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cards.forEach { card ->
            FilterChip(
                selected = card.isSameAs(selected),
                onClick = { onSelect(card) },
                label = { Text(audienceLabel(card), maxLines = 1) },
                leadingIcon = { Icon(audienceIcon(card), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            )
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
        Text(
            text = stringResource(MR.string.profile_edit_load_failed),
            style = MaterialTheme.typography.titleMedium,
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
