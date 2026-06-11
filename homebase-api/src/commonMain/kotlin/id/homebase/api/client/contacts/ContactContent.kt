package id.homebase.api.client.contacts

import kotlinx.serialization.Serializable

/**
 * The request/stored content for a V2 contact. Every field is optional.
 *
 * Serialized as camelCase via [id.homebase.api.serialization.OdinSystemSerializer], whose
 * `explicitNulls = false` config omits null fields from the JSON. That omission is load-bearing
 * for the server's merge semantics on UPDATE: a field that is absent (or empty/whitespace) means
 * "leave the existing stored value alone" — it never clears a value. Send only the fields you want
 * to set.
 *
 * [odinId] is a domain (e.g. `sam.dotyou.cloud`); omit it for a contact not tied to an identity.
 * On CREATE the server derives the contact's `uniqueId` as `md5(odinId)` when [odinId] is present,
 * otherwise a random GUID. UPDATE never re-keys a contact's identity.
 */
@Serializable
data class ContactContent(
    val odinId: String? = null,
    /** Origin marker, round-tripped: `'contact'` | `'public'` | `'user'`. */
    val source: String? = null,
    val name: ContactName? = null,
    val location: ContactLocation? = null,
    val phone: ContactPhone? = null,
    val email: ContactEmail? = null,
    val birthday: ContactBirthday? = null,
)

@Serializable
data class ContactName(
    val displayName: String? = null,
    val givenName: String? = null,
    val additionalName: String? = null,
    val surname: String? = null,
)

@Serializable
data class ContactLocation(
    val city: String? = null,
    val country: String? = null,
)

@Serializable
data class ContactPhone(
    val number: String? = null,
)

@Serializable
data class ContactEmail(
    val email: String? = null,
)

@Serializable
data class ContactBirthday(
    /** Free-form date string. */
    val date: String? = null,
)
