package id.homebase.core.emoji

import kotlinx.serialization.Serializable

@Serializable
data class Emoji(
    val emoji: String,             // The actual emoji character (or sequence)
    val label: String,               // The searchable name (e.g., "grinning face")
    val hexcode: String,             // The hex representation
    val group: Int? = null,                  // Category ID
    val subgroup: Int? = null,               // Sub-category ID
    val order: Int? = null,                  // Display order
    val tags: List<String>? = null,  // Keywords for search
    val skins: List<EmojiSkin>? = null,
    val shortcodes: List<String>? = null
)

@Serializable
data class EmojiSkin(
    val emoji: String,
    val hexcode: String,
    //val tone: Int? = null,           // Skin tone ID, can also be array
    val label: String
)