package id.homebase.core.ui.screens.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.core.image.HomebaseImage
import id.homebase.core.ui.screens.contactbook.components.formatPhoneForDisplay
import id.homebase.resources.MR
import id.homebase.resources.contactbook_detail_location
import id.homebase.resources.contactbook_detail_name
import id.homebase.resources.profile_edit_birthday
import id.homebase.resources.profile_edit_email
import id.homebase.resources.profile_edit_facebook
import id.homebase.resources.profile_edit_instagram
import id.homebase.resources.profile_edit_linkedin
import id.homebase.resources.profile_edit_nickname
import id.homebase.resources.profile_edit_phone
import id.homebase.resources.profile_edit_preview_empty
import id.homebase.resources.profile_edit_preview_section_public
import id.homebase.resources.profile_edit_preview_section_public_desc
import id.homebase.resources.profile_edit_preview_section_vetted
import id.homebase.resources.profile_edit_preview_section_vetted_desc
import id.homebase.resources.profile_edit_status
import id.homebase.resources.profile_edit_tiktok
import id.homebase.resources.profile_edit_twitter
import org.jetbrains.compose.resources.stringResource

/** One resolved field, ready to render the way contact details are: icon, label, single-line value. */
private data class PreviewRow(val icon: ImageVector, val label: String, val value: String)

/** Given/additional/surname joined into one display line — shared with [ProfileEditScreen]'s
 *  per-attribute-group row display so both screens agree on what "the name" reads as. */
internal fun profileNameValue(values: Map<ProfileField, String>): String? = listOfNotNull(
    values[ProfileField.GIVEN_NAME]?.ifBlank { null },
    values[ProfileField.ADDITIONAL_NAME]?.ifBlank { null },
    values[ProfileField.SURNAME]?.ifBlank { null },
).joinToString(" ").ifBlank { null }

/** Street lines, then "postcode city", then country, comma-joined — same single-line format
 *  ContactBookEntry.location uses for contact addresses. Shared with [ProfileEditScreen]. */
internal fun profileAddressValue(values: Map<ProfileField, String>): String? = listOfNotNull(
    values[ProfileField.ADDRESS1]?.ifBlank { null },
    values[ProfileField.ADDRESS2]?.ifBlank { null },
    listOfNotNull(values[ProfileField.POSTCODE]?.ifBlank { null }, values[ProfileField.CITY]?.ifBlank { null })
        .joinToString(" ").ifBlank { null },
    values[ProfileField.COUNTRY]?.ifBlank { null },
).joinToString(", ").ifBlank { null }

/**
 * Read-only simulation of the owner's profile, rendered contact-detail style. Public — what
 * everyone sees — is listed first; Vetted below shows everything a vetted contact sees: their own
 * Connected value where set, falling back to the Public value for any field left blank on the
 * Connected side.
 */
@Composable
internal fun ProfilePreview(
    uiState: ProfileEditUiState,
    modifier: Modifier = Modifier,
) {
    val lblName = stringResource(MR.string.contactbook_detail_name)
    val lblNickname = stringResource(MR.string.profile_edit_nickname)
    val lblStatus = stringResource(MR.string.profile_edit_status)
    val lblBirthday = stringResource(MR.string.profile_edit_birthday)
    val lblEmail = stringResource(MR.string.profile_edit_email)
    val lblPhone = stringResource(MR.string.profile_edit_phone)
    val lblLocation = stringResource(MR.string.contactbook_detail_location)
    val lblTwitter = stringResource(MR.string.profile_edit_twitter)
    val lblFacebook = stringResource(MR.string.profile_edit_facebook)
    val lblInstagram = stringResource(MR.string.profile_edit_instagram)
    val lblTiktok = stringResource(MR.string.profile_edit_tiktok)
    val lblLinkedin = stringResource(MR.string.profile_edit_linkedin)

    fun rowsFor(values: Map<ProfileField, String>): List<PreviewRow> = buildList {
        profileNameValue(values)?.let { add(PreviewRow(Icons.Outlined.Person, lblName, it)) }
        values[ProfileField.NICKNAME]?.takeIf { it.isNotBlank() }
            ?.let { add(PreviewRow(Icons.Outlined.Badge, lblNickname, it)) }
        values[ProfileField.STATUS]?.takeIf { it.isNotBlank() }
            ?.let { add(PreviewRow(Icons.Outlined.Info, lblStatus, it)) }
        values[ProfileField.BIRTHDAY]?.takeIf { it.isNotBlank() }
            ?.let { add(PreviewRow(Icons.Outlined.Cake, lblBirthday, it)) }
        values[ProfileField.EMAIL]?.takeIf { it.isNotBlank() }?.let {
            val label = values[ProfileField.EMAIL_LABEL]?.takeIf { l -> l.isNotBlank() } ?: lblEmail
            add(PreviewRow(Icons.Outlined.Email, label, it))
        }
        values[ProfileField.PHONE]?.takeIf { it.isNotBlank() }?.let {
            val label = values[ProfileField.PHONE_LABEL]?.takeIf { l -> l.isNotBlank() } ?: lblPhone
            add(PreviewRow(Icons.Outlined.Call, label, formatPhoneForDisplay(it)))
        }
        profileAddressValue(values)?.let {
            val label = values[ProfileField.ADDRESS_LABEL]?.takeIf { l -> l.isNotBlank() } ?: lblLocation
            add(PreviewRow(Icons.Outlined.LocationOn, label, it))
        }
        values[ProfileField.TWITTER]?.takeIf { it.isNotBlank() }
            ?.let { add(PreviewRow(Icons.Outlined.AlternateEmail, lblTwitter, it)) }
        values[ProfileField.FACEBOOK]?.takeIf { it.isNotBlank() }
            ?.let { add(PreviewRow(Icons.Outlined.AlternateEmail, lblFacebook, it)) }
        values[ProfileField.INSTAGRAM]?.takeIf { it.isNotBlank() }
            ?.let { add(PreviewRow(Icons.Outlined.AlternateEmail, lblInstagram, it)) }
        values[ProfileField.TIKTOK]?.takeIf { it.isNotBlank() }
            ?.let { add(PreviewRow(Icons.Outlined.AlternateEmail, lblTiktok, it)) }
        values[ProfileField.LINKEDIN]?.takeIf { it.isNotBlank() }
            ?.let { add(PreviewRow(Icons.Outlined.AlternateEmail, lblLinkedin, it)) }
    }

    val publicRows = rowsFor(uiState.anonymousValues)

    // What a vetted contact actually sees: their own Connected value where set, else the Public one.
    val resolved = (uiState.anonymousValues.keys + uiState.connectedValues.keys).associateWith { field ->
        uiState.connectedValues[field]?.takeIf { it.isNotBlank() } ?: uiState.anonymousValues[field].orEmpty()
    }
    val vettedRows = rowsFor(resolved)
    // Same fallback as every text field: no Connected-tier photo means a vetted contact just sees
    // the Public one.
    val vettedPhoto = uiState.connectedPhoto ?: uiState.anonymousPhoto

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PreviewSectionHeader(
            title = stringResource(MR.string.profile_edit_preview_section_public),
            description = stringResource(MR.string.profile_edit_preview_section_public_desc),
        )
        PreviewPhoto(uiState.anonymousPhoto)
        if (publicRows.isEmpty()) {
            PreviewEmptyMessage(stringResource(MR.string.profile_edit_preview_empty))
        } else {
            publicRows.forEach { PreviewRowItem(it) }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))

        PreviewSectionHeader(
            title = stringResource(MR.string.profile_edit_preview_section_vetted),
            description = stringResource(MR.string.profile_edit_preview_section_vetted_desc),
        )
        PreviewPhoto(vettedPhoto)
        if (vettedRows.isEmpty()) {
            PreviewEmptyMessage(stringResource(MR.string.profile_edit_preview_empty))
        } else {
            vettedRows.forEach { PreviewRowItem(it) }
        }
    }
}

@Composable
private fun PreviewPhoto(photo: ProfileAttribute?) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(96.dp).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val imageData = photo?.photoImageData()
            LaunchedEffect(photo?.id) {
                when {
                    photo == null -> Logger.d(tag = "ProfilePreview") { "no photo attribute for this tier — placeholder shown" }
                    imageData == null -> // photoImageData() already logged the specific reason.
                        Logger.w(tag = "ProfilePreview") { "photo attribute ${photo.id} present but not renderable — placeholder shown" }
                }
            }
            if (imageData != null) {
                HomebaseImage(
                    imageData = imageData,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = null,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewSectionHeader(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PreviewEmptyMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PreviewRowItem(row: PreviewRow) {
    ListItem(
        leadingContent = { Icon(row.icon, contentDescription = null) },
        overlineContent = { Text(row.label) },
        headlineContent = { SelectionContainer { Text(row.value) } },
    )
}
