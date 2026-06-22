@file:OptIn(ExperimentalSerializationApi::class)

package id.homebase.api.client.contacts

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    val name: ContactName? = null,
    /** Origin marker, round-tripped: `'contact'` | `'public'` | `'user'`. */
    val source: String? = null,
    // location/phone/email/birthday are ALWAYS present on a stored contact (rendered as `{}` when
    // empty), so a read can treat them as reliably non-null; on WRITE they're optional — omit or
    // send `{}` and the server normalizes. They stay nullable here to keep the write model uniform.
    val location: ContactLocation? = null,
    val phone: ContactPhone? = null,
    val email: ContactEmail? = null,
    val birthday: ContactBirthday? = null,
    /** Short header tagline (~<=160 chars). Distinct from the ext_data bios (see [ContactExtData]). */
    val shortBio: String? = null,
    val nickname: String? = null,
    /** Free-text status/tagline — NOT connection state (derive that live from connection/circle APIs). */
    val status: String? = null,
    /** Bare URL value; render the link yourself. */
    val link: String? = null,
    // social stays nullable (not `emptyMap()`): the serializer encodes defaults, so a non-null empty
    // default would emit on every write and the server's merge would treat it as "set this",
    // clobbering stored handles. Null omits.
    /** Social handles keyed by attribute-type-id GUID (hyphenated). Values are bare handles, not URLs. */
    val social: Map<String, String>? = null,
    /**
     * Owner-only flag — a contact either is an emergency contact or isn't, so this is a plain
     * non-null [Boolean]. [EncodeDefault.Mode.NEVER] keeps the merge contract intact: the `false`
     * default is omitted (so a write doesn't clobber the stored value), while `true` is emitted.
     * Note this can set the flag but not clear it through the merge — a `false` reads as "leave
     * alone"; clearing needs a dedicated write.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val isEmergencyContact: Boolean = false,
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
