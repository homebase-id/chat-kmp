@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.api.client.drives.files.RichTextNode
import kotlinx.coroutines.launch
import id.homebase.chat.conversationsettings.ConversationOverview
import id.homebase.chat.conversationsettings.GroupInCommonItem
import id.homebase.chat.conversationsettings.SharedMediaItem
import id.homebase.chat.widget.MediaItem
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.ImageSize
import id.homebase.api.client.contacts.ContactExperience
import id.homebase.api.client.contacts.ContactSocialNetwork
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.contactbook_detail_bio
import id.homebase.resources.contactbook_detail_social
import id.homebase.resources.contactbook_detail_circles
import id.homebase.resources.contactbook_detail_circles_connect
import id.homebase.resources.contactbook_detail_circles_empty
import id.homebase.resources.contactbook_detail_contact_details
import id.homebase.resources.contactbook_detail_experience
import id.homebase.resources.contactbook_detail_location
import id.homebase.resources.contactbook_detail_name
import id.homebase.resources.contactbook_detail_override_none
import id.homebase.resources.contactbook_detail_override_synced
import id.homebase.resources.contactbook_edit_birthday
import id.homebase.resources.contactbook_edit_email
import id.homebase.resources.contactbook_edit_phone
import id.homebase.resources.contactbook_detail_groups_connect
import id.homebase.resources.contactbook_detail_groups_empty
import id.homebase.resources.contactbook_detail_less
import id.homebase.resources.contactbook_detail_more
import id.homebase.resources.contactbook_detail_none
import id.homebase.resources.contactbook_detail_no_recent_media
import id.homebase.resources.contactbook_detail_recent_media
import id.homebase.resources.conversation_groups_in_common
import id.homebase.resources.conversation_media_see_all
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.ExperimentalUuidApi

/**
 * Shared-content overview for the 1:1 conversation: a recent-media strip plus a "See all" that
 * reaches the full shared-content screen (media, files, audio, dice rolls, locations). "See all"
 * shows whenever there's *any* shared content — not just media — so non-media items are reachable
 * even when there's nothing to strip. The empty state shows only when there's truly nothing.
 */
@Composable
fun RecentMediaSection(
    overview: ConversationOverview?,
    onMediaClick: (SharedMediaItem) -> Unit,
    onSeeAll: () -> Unit,
) {
    val media = overview?.media.orEmpty()
    val hasAnything = overview != null && (
        overview.media.isNotEmpty() || overview.files.isNotEmpty() ||
            overview.audio.isNotEmpty() || overview.diceRolls.isNotEmpty() ||
            overview.locations.isNotEmpty()
        )

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(MR.string.contactbook_detail_recent_media),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (hasAnything) {
            TextButton(onClick = onSeeAll) {
                Text(stringResource(MR.string.conversation_media_see_all))
            }
        }
    }
    when {
        media.isNotEmpty() -> LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(media.take(50)) { item ->
                SharedMediaThumb(item, size = 84.dp) { onMediaClick(item) }
            }
        }
        // Has non-media shared content but no media to strip: "See all" above leads to it.
        hasAnything -> Unit
        else -> Text(
            text = stringResource(MR.string.contactbook_detail_no_recent_media),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * "Groups in common" list. Always shows the header; falls back to an empty state, or a
 * "must be connected" hint when [isConnected] is false and there's nothing to show.
 */
@Composable
fun GroupsInCommonSection(
    groups: List<GroupInCommonItem>,
    isConnected: Boolean,
    onOpenGroup: (kotlin.uuid.Uuid) -> Unit,
) {
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = stringResource(MR.string.conversation_groups_in_common),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    Spacer(modifier = Modifier.height(8.dp))
    when {
        groups.isNotEmpty() -> groups.forEach { group ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenGroup(group.conversationId) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConversationAvatar(avatarModel = group.avatarModel, options = AvatarOptions(size = 40.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = group.name, style = MaterialTheme.typography.bodyLarge)
            }
        }

        !isConnected -> SectionHint(stringResource(MR.string.contactbook_detail_groups_connect))
        else -> SectionHint(stringResource(MR.string.contactbook_detail_groups_empty))
    }
}

/**
 * User-defined circles this contact belongs to, as chips. Always shows the header; falls
 * back to an empty state, or a "must be connected" hint when [isConnected] is false.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CirclesSection(circles: List<String>, isConnected: Boolean) {
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = stringResource(MR.string.contactbook_detail_circles),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    Spacer(modifier = Modifier.height(8.dp))
    when {
        circles.isNotEmpty() -> FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            circles.forEach { name -> CircleChip(name) }
        }

        !isConnected -> SectionHint(stringResource(MR.string.contactbook_detail_circles_connect))
        else -> SectionHint(stringResource(MR.string.contactbook_detail_circles_empty))
    }
}

/** Non-interactive pill showing a circle name (these are display tags, not actions). */
@Composable
private fun CircleChip(name: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Friendly, centered empty state for a whole tab that currently has nothing to show. Tabs are
 * always present, so this explains why one is blank rather than leaving an empty pane.
 */
@Composable
fun TabEmptyMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Muted single-line hint used by sections for their empty / not-connected states. */
@Composable
private fun SectionHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Contact fields, visible without entering edit mode. Shows the first couple of
 * present fields and tucks the rest behind a "More" toggle.
 */
@Composable
fun ContactFieldsSection(
    entry: ContactBookEntry,
    expanded: Boolean,
    onToggleMore: () -> Unit,
) {
    Text(
        text = stringResource(MR.string.contactbook_detail_contact_details),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )

    // Labels resolved here (stringResource can't be called inside the buildList builder).
    val lblName = stringResource(MR.string.contactbook_detail_name)
    val lblPhone = stringResource(MR.string.contactbook_edit_phone)
    val lblEmail = stringResource(MR.string.contactbook_edit_email)
    val lblLocation = stringResource(MR.string.contactbook_detail_location)
    val lblBirthday = stringResource(MR.string.contactbook_edit_birthday)

    // Each field carries its synced original ([DetailFieldRow.synced]) when the user has overridden
    // it, so a small peek icon can reveal "their profile says …". The full name comes first so an
    // identity contact shows its real details; the rest tuck behind "More". The Homebase ID isn't
    // repeated here — it's already shown under the name in the header.
    val o = entry.syncedOverlay
    val syncedLocation = o?.let { ov ->
        if (ov.city != null || ov.country != null) {
            listOfNotNull(ov.city?.ifBlank { null }, ov.country?.ifBlank { null }).joinToString(", ")
        } else {
            null
        }
    }
    // First and last name on one line.
    val fullName = listOfNotNull(
        entry.givenName?.ifBlank { null },
        entry.surname?.ifBlank { null },
    ).joinToString(" ").ifBlank { null }
    // Synced "their profile says …" original, only when given/surname was overridden; the
    // non-overridden part falls back to the entry's own value so the peek shows the whole name.
    val syncedName = if (o?.givenName != null || o?.surname != null) {
        listOfNotNull(
            (o?.givenName ?: entry.givenName)?.ifBlank { null },
            (o?.surname ?: entry.surname)?.ifBlank { null },
        ).joinToString(" ").ifBlank { null }
    } else {
        null
    }
    val fields = buildList {
        fullName?.let { add(DetailFieldRow(Icons.Outlined.Person, lblName, it, syncedName)) }
        entry.phone?.takeIf { it.isNotBlank() }
            ?.let { add(DetailFieldRow(Icons.Outlined.Call, lblPhone, it, o?.phone)) }
        // App-local additional phones (no synced counterpart) render as plain extra rows.
        entry.additionalPhones.filter { it.isNotBlank() }
            .forEach { add(DetailFieldRow(Icons.Outlined.Call, lblPhone, it, null)) }
        entry.email?.takeIf { it.isNotBlank() }
            ?.let { add(DetailFieldRow(Icons.Outlined.Email, lblEmail, it, o?.email)) }
        entry.additionalEmails.filter { it.isNotBlank() }
            .forEach { add(DetailFieldRow(Icons.Outlined.Email, lblEmail, it, null)) }
        entry.location?.takeIf { it.isNotBlank() }?.let {
            // Prefer the address's own label ("Home" / "Work") over the generic "Location".
            val addressLabel = entry.locationLabel?.takeIf { l -> l.isNotBlank() } ?: lblLocation
            add(DetailFieldRow(Icons.Outlined.LocationOn, addressLabel, it, syncedLocation))
        }
        entry.birthday?.takeIf { it.isNotBlank() }
            ?.let { add(DetailFieldRow(Icons.Outlined.Cake, lblBirthday, it, o?.birthday)) }
    }
    if (fields.isEmpty()) {
        Text(
            text = stringResource(MR.string.contactbook_detail_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }

    val visible = if (expanded) fields else fields.take(2)
    visible.forEach { DetailField(it.icon, it.label, it.value, it.synced) }

    if (fields.size > 2) {
        TextButton(
            onClick = onToggleMore,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(
                stringResource(
                    if (expanded) MR.string.contactbook_detail_less
                    else MR.string.contactbook_detail_more
                )
            )
        }
    }
}

/** Short free-text bio/tagline, in its own section. Renders nothing when the contact has none. */
@Composable
fun BioSection(shortBio: String?) {
    val bio = shortBio?.takeIf { it.isNotBlank() } ?: return
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = stringResource(MR.string.contactbook_detail_bio),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = bio,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/**
 * The synced "Experience" attribute (from the contact's `ext_data` payload): an optional image, a
 * short title, the Plate/Slate.js `full_bio` rendered as real rich text, and a link. Renders
 * nothing when absent.
 */
@Composable
fun ExperienceSection(experience: ContactExperience?, image: ByteArray?) {
    val exp = experience ?: return
    val title = exp.title?.takeIf { it.isNotBlank() }
    val bio = exp.fullBio?.takeIf { it.isNotEmpty() }
    val link = exp.link?.takeIf { it.isNotBlank() }
    if (title == null && bio == null && link == null && image == null) return

    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = stringResource(MR.string.contactbook_detail_experience),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        image?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        title?.let {
            Text(text = it, style = MaterialTheme.typography.titleSmall)
        }
        bio?.let { nodes ->
            val html = remember(nodes) { nodes.toExperienceHtml() }
            val state = remember(html) {
                RichTextState().apply {
                    config.listIndent = 12
                    setHtml(html)
                }
            }
            RichText(
                state = state,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        link?.let {
            val uriHandler = getUriHandler()
            val url = if (it.startsWith("http", ignoreCase = true)) it else "https://$it"
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { uriHandler.openUrl(url) },
            )
        }
    }
}

/** Convert a Plate/Slate.js node tree to the small HTML subset [RichTextState.setHtml] renders. */
private fun List<RichTextNode>.toExperienceHtml(): String =
    joinToString("") { it.toExperienceHtml() }

private fun RichTextNode.toExperienceHtml(): String {
    text?.let { return escapeExperienceHtml(it) }
    val inner = children?.joinToString("") { it.toExperienceHtml() }
        ?: value?.let { escapeExperienceHtml(it) }
        ?: ""
    return when (type) {
        "ul" -> "<ul>$inner</ul>"
        "ol" -> "<ol>$inner</ol>"
        "li" -> "<li>$inner</li>"
        "h1" -> "<h1>$inner</h1>"
        "h2" -> "<h2>$inner</h2>"
        "h3" -> "<h3>$inner</h3>"
        "blockquote" -> "<blockquote>$inner</blockquote>"
        // Slate "a" links carry no href in this data, so render their text inline rather than as a
        // dead link; the contact's experience_link is shown as a real link separately.
        "a", null -> inner
        else -> "<p>$inner</p>"
    }
}

private fun escapeExperienceHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * The contact's social/gaming handles (resolved to known networks), in its own section. Each row
 * shows the network name over the bare handle. Renders nothing when there are none.
 */
@Composable
fun SocialSection(handles: List<Pair<ContactSocialNetwork, String>>) {
    if (handles.isEmpty()) return
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = stringResource(MR.string.contactbook_detail_social),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    handles.forEach { (network, handle) ->
        ListItem(
            leadingContent = { Icon(Icons.Outlined.AlternateEmail, contentDescription = null) },
            overlineContent = { Text(network.label) },
            headlineContent = { Text(handle) },
        )
    }
}

@Composable
private fun SharedMediaThumb(item: SharedMediaItem, size: Dp, onClick: () -> Unit) {
    MediaItem(
        payload = item.payload,
        fileId = item.fileId,
        driveId = chatTargetDrive.alias,
        previewThumbnail = item.previewThumbnail,
        keyHeader = item.keyHeader,
        imageSize = ImageSize.THUMB_MEDIUM,
        isSticker = item.isSticker,
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
        sharedTransitionScope = null,
        animatedVisibilityScope = null,
    )
}

/** One contact-detail row. [synced] is non-null when the user overrode this field — it holds the
 *  synced (peer-profile) original, surfaced behind a peek icon. */
private data class DetailFieldRow(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val synced: String?,
)

@Composable
private fun DetailField(icon: ImageVector, label: String, value: String?, synced: String?) {
    if (value.isNullOrBlank()) return
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        overlineContent = { Text(label) },
        headlineContent = { Text(value) },
        trailingContent = synced?.let { { SyncedValuePeek(it) } },
    )
}

/**
 * A small icon beside an overridden field that reveals the synced (peer-profile) value on hover
 * (desktop) or tap (touch). The value is the user's; this is just the "their profile says …" peek.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncedValuePeek(syncedValue: String) {
    val shown = syncedValue.ifBlank { stringResource(MR.string.contactbook_detail_override_none) }
    val label = stringResource(MR.string.contactbook_detail_override_synced, shown)
    val tooltipState = rememberTooltipState(isPersistent = false)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(label) } },
        state = tooltipState,
    ) {
        IconButton(onClick = { scope.launch { tooltipState.show() } }) {
            Icon(
                Icons.Outlined.Sync,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

