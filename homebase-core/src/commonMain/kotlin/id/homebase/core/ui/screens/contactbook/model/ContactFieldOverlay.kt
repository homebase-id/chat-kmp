package id.homebase.core.ui.screens.contactbook.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A user's per-field override of a *connected* contact's profile-synced values.
 *
 * For a connected contact the server's `ContactEnrichmentService` re-pulls the peer's profile on
 * every sync and merges it per-leaf with `Coalesce(incoming, existing)` — the peer's published value
 * always wins, so an edit written into the contact content is silently overwritten. The one store
 * the enrichment merge never touches is this app's per-app `appData` slot, so user overrides live
 * there (bulk tier, `appextdata` payload) instead.
 *
 * Sparse: only fields the user actually changed are non-null. The same shape is reused to carry the
 * *synced originals* of the overridden fields (see [ContactBookEntry.syncedOverlay]) so the UI can
 * reveal "their profile says …" beside an overridden value.
 */
@Serializable
@Immutable
data class ContactFieldOverlay(
    val givenName: String? = null,
    val surname: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val city: String? = null,
    val country: String? = null,
    val birthday: String? = null,
    /**
     * Extra phone numbers / emails the user added beyond the single canonical slot. The contact
     * schema is single-valued everywhere (server + odin-js + client), so these are additive and live
     * only in this app's override — they render as extra rows here but don't sync into the contact's
     * `phone`/`email` field or appear in other apps.
     */
    val additionalPhones: List<String> = emptyList(),
    val additionalEmails: List<String> = emptyList(),
    /** Additive like the two above — the contact schema has no company/organization slot at all,
     *  so this is never an override of a synced value and never appears in the synced originals. */
    val organization: String? = null,
) {
    val isEmpty: Boolean
        get() = givenName == null && surname == null && phone == null && email == null &&
            city == null && country == null && birthday == null &&
            additionalPhones.isEmpty() && additionalEmails.isEmpty() && organization == null
}
