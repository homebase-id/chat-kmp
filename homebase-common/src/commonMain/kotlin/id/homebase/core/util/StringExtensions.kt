@file:Suppress("unused")

package id.homebase.core.util

fun String.isEmojiContentOnly(): Boolean {
    if (this.isBlank()) return false
    if (this.lines().all { it.isBlank() }) return false

    var i = 0
    val s = this
    var foundAny = false
    while (i < s.length) {
        val (cp, cpLen) = codePointAt(s, i)

        // Allow ASCII space (U+0020) as a separator between emoji clusters; reject other control chars
        if (cp == 0x20) {
            i += cpLen
            continue
        }
        if (cp <= 0x1F) return false

        // Handle regional indicator (flags) which are pairs of regional indicator symbols
        if (isRegionalIndicator(cp)) {
            val next = if (i + cpLen < s.length) codePointAt(s, i + cpLen) else Pair(-1, 0)
            if (next.first != -1 && isRegionalIndicator(next.first)) {
                // consume the pair
                i += cpLen + next.second
                foundAny = true
                continue
            } else {
                return false
            }
        }

        // Build an emoji cluster starting at this code point
        var clusterContainsEmoji = isEmojiBase(cp)
        var j = i + cpLen

        // Optional variation selector (U+FE0F) that forces emoji presentation
        if (j < s.length) {
            val (nextCp, nextLen) = codePointAt(s, j)
            if (nextCp == 0xFE0F) {
                clusterContainsEmoji = true
                j += nextLen
            }
        }

        // Optional emoji modifier (skin tone U+1F3FB..U+1F3FF)
        if (j < s.length) {
            val (nextCp, nextLen) = codePointAt(s, j)
            if (nextCp in 0x1F3FB..0x1F3FF) {
                clusterContainsEmoji = true
                j += nextLen
            }
        }

        // Optional enclosing keycap (U+20E3)
        if (j < s.length) {
            val (nextCp, nextLen) = codePointAt(s, j)
            if (nextCp == 0x20E3) {
                clusterContainsEmoji = true
                j += nextLen
            }
        }

        // Handle ZWJ sequences: (emoji ZWJ emoji)+
        var k = j
        var zwjFound = false
        while (k < s.length) {
            val (zwjCp, zwjLen) = codePointAt(s, k)
            if (zwjCp == 0x200D) {
                // must be followed by another emoji base / regional indicator
                val after = if (k + zwjLen < s.length) codePointAt(s, k + zwjLen) else Pair(-1, 0)
                if (after.first != -1 && (isEmojiBase(after.first) || isRegionalIndicator(after.first))) {
                    zwjFound = true
                    // include variation selector / modifiers after the joined emoji
                    var kk = k + zwjLen + after.second
                    if (kk < s.length) {
                        val (vsel, vlen) = codePointAt(s, kk)
                        if (vsel == 0xFE0F) kk += vlen
                    }
                    if (kk < s.length) {
                        val (mod, mlen) = codePointAt(s, kk)
                        if (mod in 0x1F3FB..0x1F3FF) kk += mlen
                    }
                    k = kk
                    clusterContainsEmoji = true
                    continue
                } else {
                    break
                }
            } else break
        }

        if (zwjFound) {
            i = k
            foundAny = true
            continue
        }

        if (!clusterContainsEmoji) return false

        // Accept this cluster
        i = j
        foundAny = true
    }

    return foundAny
}

// Extension to check whether a single Char is likely an emoji character (keeps previous behavior)
fun Char.isEmoji(): Boolean {
    val code = this.code
    return isEmojiBase(code) || isRegionalIndicator(code) || (code in 0xFE00..0xFE0F)
}

// Helpers operate on full Unicode code points (Int)
private fun isEmojiBase(codePoint: Int): Boolean {
    return when (codePoint) {
        in 0x1F600..0x1F64F -> true // Emoticons
        in 0x1F300..0x1F5FF -> true // Misc Symbols and Pictographs
        in 0x1F680..0x1F6FF -> true // Transport and Map Symbols
        in 0x1F900..0x1F9FF -> true // Supplemental Symbols and Pictographs
        in 0x1FA70..0x1FAFF -> true // Symbols & Pictographs Extended-A
        in 0x2600..0x26FF -> true   // Misc symbols
        in 0x2700..0x27BF -> true   // Dingbats
        in 0x1F100..0x1F1FF -> true // Enclosed Alphanumeric Supplement
        in 0x1F700..0x1F77F -> true // Misc additional block
        in 0x203C..0x3299 -> true   // various punctuation/symbols commonly used as emoji
        else -> false
    }
}

private fun isRegionalIndicator(codePoint: Int): Boolean {
    return codePoint in 0x1F1E6..0x1F1FF
}

// Read a Unicode code point at index in the string and return Pair(codePoint, charCount)
private fun codePointAt(s: String, index: Int): Pair<Int, Int> {
    val ch = s[index]
    if (ch in '\uD800'..'\uDBFF' && index + 1 < s.length) {
        val low = s[index + 1]
        if (low in '\uDC00'..'\uDFFF') {
            val high = ch.code
            val lowc = low.code
            val cp = ((high - 0xD800) shl 10) + (lowc - 0xDC00) + 0x10000
            return Pair(cp, 2)
        }
    }
    return Pair(ch.code, 1)
}

fun String.initials(): String {
    val tokens =
        this
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }

    return when {
        tokens.size >= 2 ->
            "${tokens.first().first()}${tokens.last().first()}".uppercase().trim()

        tokens.size == 1 ->
            tokens.first().first().uppercaseChar().toString().trim()

        else -> ""
    }
}