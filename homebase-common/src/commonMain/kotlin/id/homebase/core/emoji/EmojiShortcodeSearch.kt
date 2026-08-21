package id.homebase.core.emoji

/**
 * Shortest query that opens the shortcode popup. A bare `:` — and `:` plus one character —
 * matches far too much to rank usefully, and popping a list on every colon is noise.
 */
const val EmojiShortcodeMinQueryLength: Int = 2

const val EmojiShortcodeMaxSuggestions: Int = 8

/**
 * The bundled emojibase dump carries no `shortcodes` field, so `:smile` has no canonical
 * name to hit — matching runs against [Emoji.label] and [Emoji.tags] instead. That is fuzzy
 * enough that raw `filterEmojis` returns ~200 hits in dataset order for a two-letter query,
 * so rank instead: whole-label prefix beats word prefix beats exact tag, and `order`
 * (emojibase's canonical sequence, roughly most-used first) breaks ties.
 */
fun rankEmojiForShortcode(
    query: String,
    allEmojis: List<Emoji>,
    limit: Int = EmojiShortcodeMaxSuggestions,
): List<Emoji> {
    if (query.length < EmojiShortcodeMinQueryLength) return emptyList()
    if (query.all { it.isShortcodeSeparator() }) return emptyList()

    return allEmojis
        .asSequence()
        .mapNotNull { emoji ->
            val score = scoreEmoji(query, emoji) ?: return@mapNotNull null
            emoji to score
        }
        .sortedWith(compareBy({ it.second }, { it.first.order ?: Int.MAX_VALUE }))
        .take(limit)
        .map { it.first }
        .toList()
}

// Case-insensitive comparisons throughout rather than lowercase() — this runs over ~1900
// emoji and their ~11 tags each on every keystroke, and the allocations showed up first on wasm.
private fun scoreEmoji(needle: String, emoji: Emoji): Int? {
    // group == null is the 26 regional-indicator letters, group == 2 the skin-tone
    // modifiers. Both are combining pieces, never standalone suggestions.
    val group = emoji.group ?: return null
    if (group == 2) return null

    val label = emoji.label
    if (label.equals(needle, ignoreCase = true)) return 0
    if (label.startsWith(needle, ignoreCase = true)) return 1
    if (label.hasWordStartingWith(needle)) return 2

    val tags = emoji.tags
    if (tags != null) {
        var best = Int.MAX_VALUE
        for (tag in tags) {
            if (tag.equals(needle, ignoreCase = true)) {
                best = 3
                break
            }
            if (best > 4 && tag.startsWith(needle, ignoreCase = true)) best = 4
        }
        if (best != Int.MAX_VALUE) return best
    }

    if (label.contains(needle, ignoreCase = true)) return 5
    if (tags?.any { it.contains(needle, ignoreCase = true) } == true) return 6

    // Separator-insensitive fallback. Conventional shortcodes glue words together with `_`
    // while emojibase labels use spaces or hyphens, so `:thumbs_up` and `:thumbsup` have to
    // reach "thumbs up" and `:heart_eyes` has to reach "smiling face with heart-eyes".
    if (label.equalsIgnoringSeparators(needle)) return 7
    if (label.startsWithIgnoringSeparators(needle)) return 8
    if (tags?.any { it.equalsIgnoringSeparators(needle) } == true) return 9
    if (label.containsIgnoringSeparators(needle)) return 10
    return null
}

internal fun Char.isShortcodeSeparator(): Boolean = this == ' ' || this == '_' || this == '-'

/**
 * Separator- and case-insensitive comparisons that walk both strings in place. Building
 * stripped copies instead would allocate once per emoji per keystroke, and this runs the
 * full ~1900-entry list every time the query changes.
 */
private fun String.regionMatchesIgnoringSeparators(startIndex: Int, other: String): Boolean {
    var i = startIndex
    var j = 0
    while (true) {
        while (j < other.length && other[j].isShortcodeSeparator()) j++
        if (j == other.length) return true
        while (i < length && this[i].isShortcodeSeparator()) i++
        if (i == length) return false
        if (!this[i].equals(other[j], ignoreCase = true)) return false
        i++
        j++
    }
}

private fun String.startsWithIgnoringSeparators(other: String): Boolean =
    regionMatchesIgnoringSeparators(startIndex = 0, other = other)

private fun String.containsIgnoringSeparators(other: String): Boolean {
    for (start in indices) {
        if (regionMatchesIgnoringSeparators(start, other)) return true
    }
    return false
}

private fun String.equalsIgnoringSeparators(other: String): Boolean {
    var i = 0
    var j = 0
    while (true) {
        while (i < length && this[i].isShortcodeSeparator()) i++
        while (j < other.length && other[j].isShortcodeSeparator()) j++
        if (i == length && j == other.length) return true
        if (i == length || j == other.length) return false
        if (!this[i].equals(other[j], ignoreCase = true)) return false
        i++
        j++
    }
}

private fun String.hasWordStartingWith(prefix: String): Boolean {
    var index = indexOf(prefix, startIndex = 0, ignoreCase = true)
    while (index >= 0) {
        val prev = getOrNull(index - 1)
        if (prev == null || prev == ' ' || prev == '-') return true
        index = indexOf(prefix, startIndex = index + 1, ignoreCase = true)
    }
    return false
}
