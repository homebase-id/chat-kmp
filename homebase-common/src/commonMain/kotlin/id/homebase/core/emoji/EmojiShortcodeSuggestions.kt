package id.homebase.core.emoji

data class EmojiSuggestion(val shortcode: String, val emoji: String)

/**
 * Shortest query that opens the list. A bare `:` — and `:` plus one character — matches far too
 * much to rank usefully, and popping a list on every colon is noise.
 */
const val EmojiSuggestionMinQueryLength: Int = 2

const val EmojiSuggestionLimit: Int = 8

/**
 * Ranks the shortcode index from a partial `:query`. Exact beats prefix beats substring; within a
 * tier the shorter shortcode wins, then alphabetical order so the list never reshuffles between
 * identical queries.
 */
fun emojiSuggestions(
    query: String,
    shortcodes: Map<String, String>,
    limit: Int = EmojiSuggestionLimit,
): List<EmojiSuggestion> {
    if (query.length < EmojiSuggestionMinQueryLength) return emptyList()
    if (!query.all { it.isShortcodeChar() }) return emptyList()

    val needle = query.lowercase()
    return shortcodes.asSequence()
        .mapNotNull { (shortcode, emoji) ->
            val tier = when {
                shortcode == needle -> 0
                shortcode.startsWith(needle) -> 1
                shortcode.contains(needle) -> 2
                else -> return@mapNotNull null
            }
            Triple(tier, shortcode, emoji)
        }
        .sortedWith(compareBy({ it.first }, { it.second.length }, { it.second }))
        // Several shortcodes reach the same glyph (party/party_popper, smirk/smirking); showing
        // each glyph once keeps the short list varied.
        .distinctBy { it.third }
        .take(limit)
        .map { EmojiSuggestion(shortcode = it.second, emoji = it.third) }
        .toList()
}
