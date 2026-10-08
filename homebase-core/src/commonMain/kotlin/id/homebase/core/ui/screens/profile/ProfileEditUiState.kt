@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.runtime.Immutable
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.drives.isVisibleToCircle
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.screens.card.CardCircle
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Form state for the owner's standard-profile editor. Each detail is entered once ([values]) and
 * carries one [ProfileAudience] per attribute type ([audiences]) that decides which cards show it.
 * These are what the editor writes, not what a visitor sees: that is [visibleValues], chosen from
 * the stored [attributes] by their ACLs.
 *
 * There's no screen-wide Save: each attribute persists individually (see
 * [ProfileEditAction.SaveAttribute]), so [savingAttributes] tracks in-flight saves per type.
 */
@Immutable
data class ProfileEditUiState(
    val isLoading: Boolean = true,
    /** True when the initial read failed (e.g. missing ProfileDrive grant) — show retry. */
    val loadFailed: Boolean = false,

    val values: Map<ProfileField, String> = emptyMap(),
    val audiences: Map<String, ProfileAudience> = emptyMap(),

    /** The Contacts-app circles a detail can be shown to. */
    val circles: List<CardCircle> = emptyList(),

    /** Names of stored circles that have no card (user-made, other apps'), by the id the records hold. */
    val otherCircleNames: Map<String, String> = emptyMap(),

    /** One entry per stored link record plus any not yet saved; a profile can have many. */
    val links: List<LinkDraft> = emptyList(),

    /** Records of a type that disagree with the one edited (type to records); they stay until the user removes them. */
    val conflicts: Map<String, List<ProfileAttribute>> = emptyMap(),

    /** Every stored attribute (photos included), as last read or saved; [ProfilePreview] reads it. */
    val attributes: List<ProfileAttribute> = emptyList(),

    val savingAttributes: Set<String> = emptySet(),
) {
    fun value(field: ProfileField): String = values[field].orEmpty()

    fun audience(type: String): ProfileAudience = audiences[type] ?: defaultAudience(type, circles)

    /** What a viewer at [tier] sees of the saved profile; an edit shows here once it is saved. */
    fun visibleValues(tier: ProfileVisibility): Map<ProfileField, String> = attributes.visibleValues(tier)

    /** What a contact in at least one Contacts circle sees: public details plus any circle's. */
    fun visibleToCircles(): Map<ProfileField, String> =
        attributes.visibleValues { acl -> circles.any { acl.isVisibleToCircle(it.id) } }

    fun visiblePhoto(tier: ProfileVisibility): ProfileAttribute? = attributes.visiblePhoto(tier)

    fun isSaving(type: String): Boolean = type in savingAttributes
}

/** [key] is the record id once saved, a local placeholder before. */
@Immutable
data class LinkDraft(
    val key: String,
    val text: String = "",
    val target: String = "",
    val audience: ProfileAudience = ProfileAudience.Public,
) {
    fun isValid(circles: List<CardCircle>): Boolean = target.isNotBlank() && audience.isSavableWith(circles)
}

sealed interface ProfileEditAction {
    data object AddLink : ProfileEditAction
    data class LinkChanged(val key: String, val text: String, val target: String) : ProfileEditAction
    data class LinkAudienceChanged(val key: String, val audience: ProfileAudience) : ProfileEditAction
    data class SaveLink(val key: String) : ProfileEditAction
    data class RemoveLink(val key: String) : ProfileEditAction
    /** Deletes a record that disagrees with the edited one, after the user has seen its value. */
    data class DiscardConflict(val type: String, val id: Uuid) : ProfileEditAction
    data class FieldChanged(val field: ProfileField, val value: String) : ProfileEditAction
    data class AudienceChanged(val type: String, val audience: ProfileAudience) : ProfileEditAction
    /** Persists just this one attribute type — fired by a row's checkmark. */
    data class SaveAttribute(val type: String) : ProfileEditAction
    data object RetryLoadClicked : ProfileEditAction
    data object BackClicked : ProfileEditAction
}

/** Identifies which form field an edit targets, keeping the action surface flat. */
enum class ProfileField {
    GIVEN_NAME, SURNAME, ADDITIONAL_NAME,
    NICKNAME, STATUS, BIRTHDAY, BIO,
    EMAIL, EMAIL_LABEL,
    PHONE, PHONE_LABEL,
    ADDRESS_LABEL, ADDRESS1, ADDRESS2, POSTCODE, CITY, COUNTRY,
    TWITTER, FACEBOOK, INSTAGRAM, TIKTOK, LINKEDIN,
}

sealed interface ProfileEditEvent {
    /** [ProfileEditAction.SaveAttribute] for [type] finished successfully. */
    data class AttributeSaved(val type: String) : ProfileEditEvent
    /** 403 — the app lacks the ManageProfile permission. */
    data object Forbidden : ProfileEditEvent
    /** A [ProfileEditAction.SaveAttribute] failed for some other reason; its row stays open for retry. */
    data object Error : ProfileEditEvent
    data object Back : ProfileEditEvent
}
