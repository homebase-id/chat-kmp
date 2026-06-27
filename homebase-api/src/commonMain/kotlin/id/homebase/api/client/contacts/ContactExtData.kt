package id.homebase.api.client.contacts

import id.homebase.api.client.drives.files.RichText
import id.homebase.api.client.drives.files.getPlainTextFromRichText
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The **entire** `ext_data` payload of a contact file — large rich-text fields fetched on demand
 * (see [ContactRepository.loadExtData]), separate from the small header fields in [ContactContent].
 *
 * The root is always `{ "attributes": { … } }` — one wrapper key, nothing else. [attributes] maps an
 * attribute-type id (32-char lowercase hex, NO dashes) to that type's data object, stored **verbatim**
 * from the peer (the server never parses or reshapes it). The key set is open and inner fields can
 * change, so values are kept as raw [JsonElement] and known types are decoded lazily and defensively —
 * unknown ids and unknown inner fields are tolerated and ignored (forward-compatible).
 *
 * "Payload absent" (a contact with no extended data has no `ext_data` payload at all) is represented
 * by [ContactRepository.loadExtData] returning null — treat it as empty, not an error.
 */
@Serializable
data class ContactExtData(
    val attributes: Map<String, JsonElement> = emptyMap(),
) {
    /** Experience attribute, or null if this contact has none. */
    val experience: ContactExperience? get() = decode(EXPERIENCE_TYPE_ID)

    /** Bio attribute, or null if this contact has none. */
    val bio: ContactBio? get() = decode(BIO_TYPE_ID)

    private inline fun <reified T> decode(typeId: String): T? {
        val element = attributes[typeId] ?: return null
        return runCatching { OdinSystemSerializer.json.decodeFromJsonElement<T>(element) }.getOrNull()
    }

    companion object {
        const val EXPERIENCE_TYPE_ID = "65635623682c2fadd2767d424f53690f"
        const val BIO_TYPE_ID = "2cd30a58568dc333237944481aeb9ff1"
    }
}

/**
 * Experience attribute (`65635623682c2fadd2767d424f53690f`).
 *
 * ⚠️ Its [title] (`short_bio`) is a **plain string**; the [ContactBio.shortBio] of the same field name
 * on the Bio type is a rich-text array instead. Disambiguate by the attribute type id (the map key),
 * never by the field name. The top-level [ContactContent.shortBio] is a third, separate thing.
 */
@Serializable
data class ContactExperience(
    /** Plain-string title. */
    @SerialName("short_bio") val title: String? = null,
    /** Rich-text node array. */
    @SerialName("full_bio") val fullBio: RichText? = null,
    @SerialName("experience_link") val link: String? = null,
    /** Reference to an image payload key — the image bytes are not in this JSON. */
    @SerialName("experience_image") val imageKey: String? = null,
) {
    /** [fullBio] flattened to plain text for simple rendering, or null if empty. */
    val fullBioText: String? get() = getPlainTextFromRichText(fullBio, keepNewLines = true)
}

/**
 * Bio attribute (`2cd30a58568dc333237944481aeb9ff1`). Its [shortBio] is a **rich-text array**
 * (unlike Experience's plain-string `short_bio`).
 */
@Serializable
data class ContactBio(
    @SerialName("short_bio") val shortBio: RichText? = null,
) {
    /** [shortBio] flattened to plain text for simple rendering, or null if empty. */
    val shortBioText: String? get() = getPlainTextFromRichText(shortBio, keepNewLines = true)
}
