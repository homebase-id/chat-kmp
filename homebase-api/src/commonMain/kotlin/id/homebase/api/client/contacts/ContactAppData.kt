@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Per-app contact "app-data" — an app's own private slot on a contact record, in two tiers stored
 * under the same contact file:
 *
 *  - **Inline tier** (≤ 200 bytes): rides in the contact content as [ContactContent.appData], so the
 *    contacts list query already returns it. Read with [ContactContent.appDataFor]; written via
 *    [ContactsProvider.setContactAppData] / [ContactsProvider.deleteContactAppData].
 *  - **Bulk tier** (≤ 256 KB): the on-demand [ContactsProvider.CONTACT_APP_EXT_DATA_PAYLOAD_KEY]
 *    payload, shaped as [ContactAppExtData]. Read with [ContactRepository.loadAppExtData]; written via
 *    [ContactsProvider.setContactAppExtData] / [ContactsProvider.deleteContactAppExtData].
 *
 * Pick a tier by size and never store the same value in both.
 *
 * ⚠️ This is **not per-app isolated and not zero-knowledge**: every slot is encrypted under the
 * contact **file** key, so any app with read access to the contact drive can read every app's data.
 * For genuinely sensitive values the app must encrypt them itself before writing (the server stores
 * the bytes verbatim).
 *
 * ⚠️ The slot value is an **opaque string**. Structured data is double-encoded: serialize to JSON on
 * write, parse from JSON on read.
 */

/**
 * The entire bulk-tier (`appextdata`) payload: one object mapping appId → that app's opaque string.
 * Keys are canonical lowercase hyphenated UUID strings (e.g. `11111111-2222-3333-4444-555555555555`).
 */
@Serializable
data class ContactAppExtData(
    val appData: Map<String, String> = emptyMap(),
)

/**
 * Inline-tier read: this app's opaque string, or null when nothing has been written for [appId].
 * [appId] may be given dashless or hyphenated; it is normalized to the canonical map-key form.
 */
fun ContactContent.appDataFor(appId: String): String? =
    appData?.get(appId.toCanonicalAppId())

/** Convenience: inline-tier read straight off a [Contact]. */
fun Contact.appDataFor(appId: String): String? = content.appDataFor(appId)

/**
 * Normalizes an appId to the server's map-key form: a canonical lowercase hyphenated UUID. App
 * registrations carry the id dashless (e.g. `AppConfig.APP_ID = "2d78140138044b57b4aad8e4e2ef39f4"`),
 * but app-data maps are keyed hyphenated (`2d781401-3804-4b57-b4aa-d8e4e2ef39f4`). Accepts either
 * form; falls back to a lowercased copy if it isn't a parseable UUID.
 */
fun String.toCanonicalAppId(): String =
    (runCatching { Uuid.parseHex(this) }.getOrNull()
        ?: runCatching { Uuid.parse(this) }.getOrNull())
        ?.toString()
        ?: lowercase()

/**
 * Thrown when a write exceeds its tier's size cap (server `MaxContentLengthExceeded`, HTTP 400): the
 * inline tier caps at 200 bytes UTF-8, the bulk tier at 256 KB. The remedy is to use the bulk tier
 * (or, if already bulk, to shrink the value).
 */
class ContactAppDataTooLargeException(message: String) : Exception(message)
