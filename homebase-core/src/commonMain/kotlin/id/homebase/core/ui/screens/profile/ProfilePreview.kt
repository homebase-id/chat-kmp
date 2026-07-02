@file:OptIn(ExperimentalMaterial3Api::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.resources.MR
import id.homebase.resources.profile_edit_additional_name
import id.homebase.resources.profile_edit_address1
import id.homebase.resources.profile_edit_address2
import id.homebase.resources.profile_edit_address_label
import id.homebase.resources.profile_edit_birthday
import id.homebase.resources.profile_edit_city
import id.homebase.resources.profile_edit_country
import id.homebase.resources.profile_edit_email
import id.homebase.resources.profile_edit_email_label
import id.homebase.resources.profile_edit_facebook
import id.homebase.resources.profile_edit_given_name
import id.homebase.resources.profile_edit_instagram
import id.homebase.resources.profile_edit_linkedin
import id.homebase.resources.profile_edit_nickname
import id.homebase.resources.profile_edit_phone
import id.homebase.resources.profile_edit_phone_label
import id.homebase.resources.profile_edit_postcode
import id.homebase.resources.profile_edit_preview_connection_only_value
import id.homebase.resources.profile_edit_preview_empty
import id.homebase.resources.profile_edit_preview_tier_public
import id.homebase.resources.profile_edit_preview_tier_connected
import id.homebase.resources.profile_edit_status
import id.homebase.resources.profile_edit_surname
import id.homebase.resources.profile_edit_tiktok
import id.homebase.resources.profile_edit_twitter
import org.jetbrains.compose.resources.stringResource

/** One field resolved for the currently-previewed [tier] — [isConnectedOverride] is true only when
 *  a Connected preview is showing a genuinely different value than the Anonymous one. */
private data class PreviewField(val label: String, val value: String, val isConnectedOverride: Boolean)
/** One attribute group's fields, rendered together (same grouping as the editor's [FieldGroup] cards). */
private data class PreviewGroup(val fields: List<PreviewField>)

/**
 * Read-only simulation of what an Anonymous visitor or a Connected contact sees. Each field
 * resolves independently: Connected preview shows that field's Connected value if non-blank, else
 * falls back to its Anonymous value — the fallback described in the profile editor's design, applied
 * per field rather than per whole attribute group (a single group like Address can have some fields
 * inherited from Anonymous and one overridden for Connected at the same time).
 */
@Composable
internal fun ProfilePreview(
    uiState: ProfileEditUiState,
    tier: ProfileVisibility,
    onTierChange: (ProfileVisibility) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun resolve(field: ProfileField): Pair<String, Boolean> {
        val anon = uiState.anonymousValues[field].orEmpty()
        if (tier == ProfileVisibility.ANONYMOUS) return anon to false
        val conn = uiState.connectedValues[field].orEmpty()
        val useConnected = conn.isNotBlank()
        return (if (useConnected) conn else anon) to (useConnected && conn != anon)
    }

    fun field(field: ProfileField, label: String): PreviewField? {
        val (value, isOverride) = resolve(field)
        return if (value.isBlank()) null else PreviewField(label, value, isOverride)
    }

    val groups = listOf(
        PreviewGroup(listOfNotNull(
            field(ProfileField.GIVEN_NAME, stringResource(MR.string.profile_edit_given_name)),
            field(ProfileField.SURNAME, stringResource(MR.string.profile_edit_surname)),
            field(ProfileField.ADDITIONAL_NAME, stringResource(MR.string.profile_edit_additional_name)),
        )),
        PreviewGroup(listOfNotNull(field(ProfileField.NICKNAME, stringResource(MR.string.profile_edit_nickname)))),
        PreviewGroup(listOfNotNull(field(ProfileField.STATUS, stringResource(MR.string.profile_edit_status)))),
        PreviewGroup(listOfNotNull(field(ProfileField.BIRTHDAY, stringResource(MR.string.profile_edit_birthday)))),
        PreviewGroup(listOfNotNull(
            field(ProfileField.EMAIL, stringResource(MR.string.profile_edit_email)),
            field(ProfileField.EMAIL_LABEL, stringResource(MR.string.profile_edit_email_label)),
        )),
        PreviewGroup(listOfNotNull(
            field(ProfileField.PHONE, stringResource(MR.string.profile_edit_phone)),
            field(ProfileField.PHONE_LABEL, stringResource(MR.string.profile_edit_phone_label)),
        )),
        PreviewGroup(listOfNotNull(
            field(ProfileField.ADDRESS_LABEL, stringResource(MR.string.profile_edit_address_label)),
            field(ProfileField.ADDRESS1, stringResource(MR.string.profile_edit_address1)),
            field(ProfileField.ADDRESS2, stringResource(MR.string.profile_edit_address2)),
            field(ProfileField.POSTCODE, stringResource(MR.string.profile_edit_postcode)),
            field(ProfileField.CITY, stringResource(MR.string.profile_edit_city)),
            field(ProfileField.COUNTRY, stringResource(MR.string.profile_edit_country)),
        )),
        PreviewGroup(listOfNotNull(field(ProfileField.TWITTER, stringResource(MR.string.profile_edit_twitter)))),
        PreviewGroup(listOfNotNull(field(ProfileField.FACEBOOK, stringResource(MR.string.profile_edit_facebook)))),
        PreviewGroup(listOfNotNull(field(ProfileField.INSTAGRAM, stringResource(MR.string.profile_edit_instagram)))),
        PreviewGroup(listOfNotNull(field(ProfileField.TIKTOK, stringResource(MR.string.profile_edit_tiktok)))),
        PreviewGroup(listOfNotNull(field(ProfileField.LINKEDIN, stringResource(MR.string.profile_edit_linkedin)))),
    ).filter { it.fields.isNotEmpty() }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        PreviewTierSelector(tier, onTierChange, modifier = Modifier.padding(vertical = 12.dp))

        if (groups.isEmpty()) {
            Text(
                text = stringResource(MR.string.profile_edit_preview_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            groups.forEach { PreviewGroupCard(it) }
        }
    }
}

@Composable
private fun PreviewTierSelector(
    tier: ProfileVisibility,
    onTierChange: (ProfileVisibility) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = tier == ProfileVisibility.ANONYMOUS,
            onClick = { onTierChange(ProfileVisibility.ANONYMOUS) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text(stringResource(MR.string.profile_edit_preview_tier_public)) },
        )
        SegmentedButton(
            selected = tier == ProfileVisibility.CONNECTED,
            onClick = { onTierChange(ProfileVisibility.CONNECTED) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text(stringResource(MR.string.profile_edit_preview_tier_connected)) },
        )
    }
}

@Composable
private fun PreviewGroupCard(group: PreviewGroup) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        group.fields.forEach { field ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (field.isConnectedOverride) {
                    Text(
                        text = stringResource(MR.string.profile_edit_preview_connection_only_value),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(text = field.value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
