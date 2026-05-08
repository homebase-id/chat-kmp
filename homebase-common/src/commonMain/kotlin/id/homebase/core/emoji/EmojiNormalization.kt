package id.homebase.core.emoji

object EmojiNormalization {
    private const val VS15 = '︎'
    private const val VS16 = '️'

    fun normalize(emoji: String): String =
        emoji.filterNot { it == VS15 || it == VS16 }

    fun equalsEmoji(a: String, b: String): Boolean =
        normalize(a) == normalize(b)

    fun List<String>.distinctByEmoji(): List<String> {
        val seen = HashSet<String>()
        return filter { seen.add(normalize(it)) }
    }

    fun Iterable<String>.containsEmoji(emoji: String): Boolean {
        val target = normalize(emoji)
        return any { normalize(it) == target }
    }
}
