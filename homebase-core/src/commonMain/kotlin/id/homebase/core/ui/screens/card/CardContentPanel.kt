@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalUuidApi::class, ExperimentalLayoutApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.core.ui.screens.profile.ATTRIBUTE_SPECS
import id.homebase.core.ui.screens.profile.AttributeFields
import id.homebase.core.ui.screens.profile.AudienceBadge
import id.homebase.core.ui.screens.profile.AudiencePopover
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

internal val CARD_CONTENT_BODY_HEIGHT = 256.dp

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
    fun available(kind: AddKind) = when (kind) {
        AddKind.Link -> true
        AddKind.Social -> missingSocials.isNotEmpty()
        else -> kind.type !in present
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
        Text(
            text = stringResource(MR.string.profile_card_content_add_title),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AddKind.entries.forEach { kind ->
                val label = stringResource(addLabel(kind))
                AssistChip(
                    onClick = { adding = kind },
                    enabled = enabled && available(kind),
                    label = { Text(label) },
                    leadingIcon = { Icon(addIcon(kind), contentDescription = null) },
                )
            }
        }
        if (items.isEmpty()) {
            Text(
                text = stringResource(MR.string.profile_card_content_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        items.forEach { item ->
            ContentRow(
                item = item,
                circles = circles,
                enabled = enabled,
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
private fun ContentRow(
    item: CardContentItem,
    circles: List<CardCircle>,
    enabled: Boolean,
    popoverOpen: Boolean,
    onOpenPopover: () -> Unit,
    onClosePopover: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onAudience: (ProfileAudience) -> Unit,
) {
    val label = stringResource(typeLabel(item.type))
    val switchLabel = stringResource(MR.string.profile_card_content_switch, item.text)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(typeIcon(item.type), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        overlineContent = { Text(label) },
        headlineContent = { Text(item.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (item.locked) {
            {
                Box {
                    Text(
                        text = stringResource(MR.string.profile_card_content_public_hint),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(enabled = enabled, onClick = onOpenPopover).minimumInteractiveComponentSize(),
                    )
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
        } else {
            null
        },
        trailingContent = {
            Switch(
                checked = item.shown,
                onCheckedChange = onToggle,
                enabled = enabled && !item.locked,
                modifier = Modifier.semantics { contentDescription = switchLabel },
            )
        },
    )
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
