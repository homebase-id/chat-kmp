package id.homebase.core.ui.screens.profile

import androidx.compose.runtime.Immutable

/**
 * Flat form state for the owner's standard-profile editor. Every field is a plain string the user
 * edits; the [ProfileEditViewModel] maps each group of fields onto a profile attribute on save.
 *
 * The loaded attributes (id / versionTag / visibility / unmodelled data keys) live in the ViewModel,
 * not here — this state is purely what the form shows and binds to.
 */
@Immutable
data class ProfileEditUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    /** True when the initial attribute read failed (e.g. missing ProfileDrive grant) — show retry. */
    val loadFailed: Boolean = false,

    // Name
    val givenName: String = "",
    val surname: String = "",
    val additionalName: String = "",

    val nickName: String = "",
    val status: String = "",
    val birthday: String = "",

    // Email
    val email: String = "",
    val emailLabel: String = "",

    // Phone
    val phone: String = "",
    val phoneLabel: String = "",

    // Address
    val addressLabel: String = "",
    val address1: String = "",
    val address2: String = "",
    val postcode: String = "",
    val city: String = "",
    val country: String = "",

    // Socials (handle only)
    val twitter: String = "",
    val facebook: String = "",
    val instagram: String = "",
    val tiktok: String = "",
    val linkedin: String = "",
) {
    val canSave: Boolean get() = !isLoading && !isSaving && !loadFailed
}

sealed interface ProfileEditAction {
    data class FieldChanged(val field: ProfileField, val value: String) : ProfileEditAction
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
