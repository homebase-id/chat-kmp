package id.homebase.chat.services.convo.contact

import kotlinx.serialization.Serializable

/**
 * Base64-encoded image bytes attached to a contact. Mirrors the TypeScript `RawContact.image`
 * shape. The `content` is base64 of the raw image; the `contentType` is the MIME type
 * ("image/jpeg" etc.).
 *
 * When a contact is saved with an image present, the image is stripped from the header
 * content and uploaded as a separate encrypted payload under [ContactProtocol.ProfileImageKey].
 */
@Serializable
data class ContactImage(
    val content: String,
    val contentType: String
)
