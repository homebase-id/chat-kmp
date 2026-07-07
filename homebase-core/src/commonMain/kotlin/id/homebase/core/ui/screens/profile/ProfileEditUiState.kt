package id.homebase.core.ui.screens.profile

import androidx.compose.runtime.Immutable
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.screens.contactbook.ContactFieldValidation

/**
 * Form state for the owner's standard-profile editor. Every field has an independent value per
 * visibility tier — [anonymousValues] shown to everyone, [connectedValues] shown only to connected
 * contacts — since each tier is backed by its own ACL-gated [id.homebase.api.client.profile.ProfileAttribute]
 * record (see [ProfileEditViewModel]). A blank Connected value is not a stored override; it falls
 * back to the Anonymous value at *display* time (see [id.homebase.core.ui.screens.profile.ProfilePreview]),
 * not here — [value] never substitutes across tiers.
 *
 * The loaded attributes (id / versionTag / unmodelled data keys, per tier) live in the ViewModel,
 * not here — this state is purely what the form shows and binds to.
 */
@Immutable
data class ProfileEditUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    /** True when the initial attribute read failed (e.g. missing ProfileDrive grant) — show retry. */
    val loadFailed: Boolean = false,

    val anonymousValues: Map<ProfileField, String> = emptyMap(),
    val connectedValues: Map<ProfileField, String> = emptyMap(),

    /** The owner's [id.homebase.api.client.profile.ProfileAttributeTypes.PHOTO] attribute at each
     *  tier, if one is set — null means no photo uploaded for that tier. Managed by the dedicated
     *  avatar editor ([ProfileAvatarEditViewModel]); read-only here, just for [ProfilePreview]. */
    val anonymousPhoto: ProfileAttribute? = null,
    val connectedPhoto: ProfileAttribute? = null,
) {
    /** Raw per-tier lookup — no cross-tier fallback; "" if [field] has no value in [tier]. */
    fun value(field: ProfileField, tier: ProfileVisibility): String =
        (if (tier == ProfileVisibility.ANONYMOUS) anonymousValues else connectedValues)[field].orEmpty()

    val emailValid: Boolean get() =
        ContactFieldValidation.isValidEmail(value(ProfileField.EMAIL, ProfileVisibility.ANONYMOUS)) &&
            ContactFieldValidation.isValidEmail(value(ProfileField.EMAIL, ProfileVisibility.CONNECTED))
    val phoneValid: Boolean get() =
        ContactFieldValidation.isValidPhone(value(ProfileField.PHONE, ProfileVisibility.ANONYMOUS)) &&
            ContactFieldValidation.isValidPhone(value(ProfileField.PHONE, ProfileVisibility.CONNECTED))

    // Legacy data may not be E.164/well-formed; show it but block Save until corrected.
    val canSave: Boolean get() = !isLoading && !isSaving && !loadFailed && emailValid && phoneValid
}

sealed interface ProfileEditAction {
    data class FieldChanged(val field: ProfileField, val tier: ProfileVisibility, val value: String) : ProfileEditAction
    data object SaveClicked : ProfileEditAction
    data object RetryLoadClicked : ProfileEditAction
    data object BackClicked : ProfileEditAction
}

/** Identifies which form field an edit targets, keeping the action surface flat. */
enum class ProfileField {
    GIVEN_NAME, SURNAME, ADDITIONAL_NAME,
    NICKNAME, STATUS, BIRTHDAY,
    EMAIL, EMAIL_LABEL,
    PHONE, PHONE_LABEL,
    ADDRESS_LABEL, ADDRESS1, ADDRESS2, POSTCODE, CITY, COUNTRY,
    TWITTER, FACEBOOK, INSTAGRAM, TIKTOK, LINKEDIN,
}

sealed interface ProfileEditEvent {
    /** All changed attributes saved — pop back. */
    data object Saved : ProfileEditEvent
    /** 403 — the app lacks the ManageProfile permission. */
    data object Forbidden : ProfileEditEvent
    /** A save failed for some other reason; the form stays open for retry. */
    data object Error : ProfileEditEvent
    data object Back : ProfileEditEvent
}
