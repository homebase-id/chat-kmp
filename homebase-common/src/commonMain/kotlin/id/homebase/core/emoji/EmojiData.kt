package id.homebase.core.emoji

/**
 * Container for emoji data with groups
 */
data class EmojiData(
    val emojis: List<Emoji>,
    val groups: List<EmojiGroup>
)
