package id.homebase.core.ui.screens.profile

import androidx.compose.runtime.Immutable
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileVisibility

/**
 * Form state for the owner's standard-profile editor. Every field has an independent value per
 * visibility tier — [anonymousValues] shown to everyone, [connectedValues] shown only to connected
 * contacts — since each tier is backed by its own ACL-gated [id.homebase.api.client.profile.ProfileAttribute]
 * record (see [ProfileEditViewModel]). These two maps are what the editor writes, not what a
 * visitor sees: that is [visibleValues], chosen from the stored [attributes] by their ACLs.
 *
 * There's no screen-wide Save: each attribute persists individually (see
 * [ProfileEditAction.SaveAttribute]), so [savingAttributes] tracks in-flight saves per
 * (attribute type, tier) pair rather than a single global flag.
 *
 * The record each tier's edits are written to (id / versionTag / unmodelled data keys) lives in the
 * ViewModel, not here.
 */
@Immutable
data class ProfileEditUiState(
    val isLoading: Boolean = true,
    /** True when the initial attribute read failed (e.g. missing ProfileDrive grant) — show retry. */
    val loadFailed: Boolean = false,

    val anonymousValues: Map<ProfileField, String> = emptyMap(),
    val connectedValues: Map<ProfileField, String> = emptyMap(),

    /** Every stored attribute (photos included), as last read or saved; [ProfilePreview] reads it. */
    val attributes: List<ProfileAttribute> = emptyList(),

    /** (attribute type, tier) pairs whose [ProfileEditAction.SaveAttribute] is currently in flight. */
    val savingAttributes: Set<Pair<String, ProfileVisibility>> = emptySet(),

    /** Dark launch: off keeps main's "Vetted" wording. */
    val reviewEnabled: Boolean = false,
) {
    /** Raw per-tier lookup — no cross-tier fallback; "" if [field] has no value in [tier]. */
    fun value(field: ProfileField, tier: ProfileVisibility): String =
        (if (tier == ProfileVisibility.ANONYMOUS) anonymousValues else connectedValues)[field].orEmpty()

    /** What a viewer at [tier] sees of the saved profile; an edit shows here once it is saved. */
    fun visibleValues(tier: ProfileVisibility): Map<ProfileField, String> = attributes.visibleValues(tier)

    fun visiblePhoto(tier: ProfileVisibility): ProfileAttribute? = attributes.visiblePhoto(tier)

    fun isSaving(type: String, tier: ProfileVisibility): Boolean = (type to tier) in savingAttributes
}

sealed interface ProfileEditAction {
    data class FieldChanged(val field: ProfileField, val tier: ProfileVisibility, val value: String) : ProfileEditAction
    /** Persists just this one attribute type's [tier] record — fired by a row's checkmark. */
    data class SaveAttribute(val type: String, val tier: ProfileVisibility) : ProfileEditAction
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
    /** [ProfileEditAction.SaveAttribute] for (type, tier) finished successfully — collapse its row. */
    data class AttributeSaved(val type: String, val tier: ProfileVisibility) : ProfileEditEvent
    /** 403 — the app lacks the ManageProfile permission. */
    data object Forbidden : ProfileEditEvent
    /** A [ProfileEditAction.SaveAttribute] failed for some other reason; its row stays open for retry. */
    data object Error : ProfileEditEvent
    data object Back : ProfileEditEvent
}
