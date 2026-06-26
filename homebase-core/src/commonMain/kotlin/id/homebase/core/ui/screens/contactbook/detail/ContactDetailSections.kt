@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.chat.conversationsettings.ConversationOverview
import id.homebase.chat.conversationsettings.GroupInCommonItem
import id.homebase.chat.conversationsettings.SharedMediaItem
import id.homebase.chat.widget.MediaItem
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ConversationAvatar
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.ImageSize
import id.homebase.api.client.contacts.ContactSocialNetwork
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.resources.MR
import id.homebase.resources.contactbook_detail_bio
import id.homebase.resources.contactbook_detail_social
import id.homebase.resources.contactbook_detail_block
import id.homebase.resources.contactbook_detail_circles
import id.homebase.resources.contactbook_detail_circles_connect
import id.homebase.resources.contactbook_detail_circles_empty
import id.homebase.resources.contactbook_detail_contact_details
import id.homebase.resources.contactbook_detail_location
import id.homebase.resources.contactbook_edit_birthday
import id.homebase.resources.contactbook_edit_email
import id.homebase.resources.contactbook_edit_given_name
import id.homebase.resources.contactbook_edit_odinid
import id.homebase.resources.contactbook_edit_phone
import id.homebase.resources.contactbook_edit_surname
import id.homebase.resources.contactbook_detail_danger_zone
import id.homebase.resources.contactbook_detail_groups_connect
import id.homebase.resources.contactbook_detail_groups_empty
import id.homebase.resources.contactbook_detail_delete
import id.homebase.resources.contactbook_detail_disconnect
import id.homebase.resources.contactbook_detail_less
import id.homebase.resources.contactbook_detail_more
import id.homebase.resources.contactbook_detail_none
import id.homebase.resources.contactbook_detail_no_recent_media
import id.homebase.resources.contactbook_detail_recent_media
import id.homebase.resources.contactbook_detail_sync
import id.homebase.resources.contactbook_detail_unblock
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
    val lblFirst = stringResource(MR.string.contactbook_edit_given_name)
    val lblLast = stringResource(MR.string.contactbook_edit_surname)
    val lblId = stringResource(MR.string.contactbook_edit_odinid)
    val lblPhone = stringResource(MR.string.contactbook_edit_phone)
    val lblEmail = stringResource(MR.string.contactbook_edit_email)
    val lblLocation = stringResource(MR.string.contactbook_detail_location)
    val lblBirthday = stringResource(MR.string.contactbook_edit_birthday)

    // Each field is (icon, label, value). Name parts come first so an identity contact shows its
    // real details, not just the Homebase ID; the rest tuck behind "More".
    val fields = buildList {
        entry.givenName?.takeIf { it.isNotBlank() }?.let { add(Triple(Icons.Outlined.Person, lblFirst, it)) }
        entry.surname?.takeIf { it.isNotBlank() }?.let { add(Triple(Icons.Outlined.Person, lblLast, it)) }
        entry.odinId?.takeIf { it.isNotBlank() }?.let { add(Triple(Icons.Outlined.AlternateEmail, lblId, it)) }
        entry.phone?.takeIf { it.isNotBlank() }?.let { add(Triple(Icons.Outlined.Call, lblPhone, it)) }
        entry.email?.takeIf { it.isNotBlank() }?.let { add(Triple(Icons.Outlined.Email, lblEmail, it)) }
        entry.location?.takeIf { it.isNotBlank() }?.let {
            // Prefer the address's own label ("Home" / "Work") over the generic "Location".
            val addressLabel = entry.locationLabel?.takeIf { l -> l.isNotBlank() } ?: lblLocation
            add(Triple(Icons.Outlined.LocationOn, addressLabel, it))
        }
        entry.birthday?.takeIf { it.isNotBlank() }?.let { add(Triple(Icons.Outlined.Cake, lblBirthday, it)) }
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
    visible.forEach { (icon, label, value) -> DetailField(icon, label, value) }

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

/** Management actions (gated by contact type / connection status). */
@Composable
fun ManagementSection(
    uiState: ContactDetailUiState,
    onAction: (ContactDetailAction) -> Unit,
) {
    // Pull the latest public profile into this contact — only meaningful for Homebase identities.
    if (uiState.hasOdinId) {
        ManagementAction(
            Icons.Outlined.Sync,
            stringResource(MR.string.contactbook_detail_sync),
        ) { onAction(ContactDetailAction.SyncClicked) }
    }

    // Danger zone — set apart with a divider so it's clearly separated from the rest.
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(MR.string.contactbook_detail_danger_zone),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    if (uiState.hasOdinId) {
        if (uiState.isConnected) {
            ManagementAction(
                Icons.Outlined.PersonRemove,
                stringResource(MR.string.contactbook_detail_disconnect),
                destructive = true,
            ) { onAction(ContactDetailAction.DisconnectClicked) }
        }
        if (uiState.isBlocked) {
            ManagementAction(
                Icons.Outlined.Block,
                stringResource(MR.string.contactbook_detail_unblock),
            ) { onAction(ContactDetailAction.UnblockClicked) }
        } else {
            ManagementAction(
                Icons.Outlined.Block,
                stringResource(MR.string.contactbook_detail_block),
                destructive = true,
            ) { onAction(ContactDetailAction.BlockClicked) }
        }
    }
    ManagementAction(
        Icons.Outlined.Delete,
        stringResource(MR.string.contactbook_detail_delete),
        destructive = true,
    ) { onAction(ContactDetailAction.DeleteClicked) }
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

@Composable
private fun DetailField(icon: ImageVector, label: String, value: String?) {
    if (value.isNullOrBlank()) return
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        overlineContent = { Text(label) },
        headlineContent = { Text(value) },
    )
}

@Composable
private fun ManagementAction(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(label, color = tint) },
    )
}
