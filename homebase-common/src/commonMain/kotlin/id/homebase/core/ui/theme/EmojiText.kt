package id.homebase.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Applies the bundled colour-emoji font to the emoji in [this] and nothing else.
 *
 * Setting the emoji font as the typography face made every glyph resolve through it, which
 * wrecked ordinary text — Noto Color Emoji has no Latin. Compose picks a single font per
 * span rather than falling back per glyph, so the only way to leave text alone is to scope
 * the font to the emoji runs themselves.
 *
 * On every platform except web [emojiFontFamily] is null and this returns the string with no
 * styling at all, so nothing changes there.
 */
@Composable
fun String.withEmojiFont(): AnnotatedString = withEmojiFont(emojiFontFamily())

/** [withEmojiFont] for callers that already hold the family and want to memoize the result. */
fun String.withEmojiFont(family: FontFamily?): AnnotatedString {
    if (family == null) return AnnotatedString(this)
    val runs = emojiRuns(this)
    if (runs.isEmpty()) return AnnotatedString(this)
    val emojiStyle = SpanStyle(fontFamily = family)
    return buildAnnotatedString {
        var at = 0
        for (run in runs) {
            if (run.first > at) append(this@withEmojiFont.substring(at, run.first))
            withStyle(emojiStyle) { append(this@withEmojiFont.substring(run.first, run.last + 1)) }
            at = run.last + 1
        }
        if (at < this@withEmojiFont.length) append(this@withEmojiFont.substring(at))
    }
}

/**
 * [withEmojiFont] for text that already carries styling — the markdown-rendered message body,
 * for one. Existing spans are preserved; the emoji font is layered over the emoji runs only.
 */
@Composable
fun AnnotatedString.withEmojiFont(): AnnotatedString = withEmojiFont(emojiFontFamily())

/** [withEmojiFont] for callers that already hold the family and want to memoize the result. */
fun AnnotatedString.withEmojiFont(family: FontFamily?): AnnotatedString {
    if (family == null) return this
    val runs = emojiRuns(this.text)
    if (runs.isEmpty()) return this
    val emojiStyle = SpanStyle(fontFamily = family)
    return buildAnnotatedString {
        append(this@withEmojiFont)
        for (run in runs) addStyle(emojiStyle, run.first, run.last + 1)
    }
}

/**
 * Character ranges in [text] that should be drawn with an emoji font, as inclusive indices.
 *
 * Adjacent emoji characters are merged into one run so a ZWJ sequence (👨‍👩‍👧), a skin-tone
 * modifier or a keycap stays in a single span — splitting one would break the ligature and
 * render the parts separately.
 *
 * Deliberately conservative: only code points that are unambiguously emoji are matched. Ranges
 * that overlap ordinary typography — arrows, punctuation, digits — are matched only when
 * followed by U+FE0F, the emoji-presentation selector, so `3.` never turns into 3️⃣.
 */
internal fun emojiRuns(text: String): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val runs = mutableListOf<IntRange>()
    var start = -1
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        val high = ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
        val cp = if (high) {
            0x10000 + ((ch.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
        } else {
            ch.code
        }
        val width = if (high) 2 else 1
        // A base code point that is emoji on its own, or a joiner/modifier continuing a run,
        // or a text-default symbol explicitly promoted by the following U+FE0F.
        val promoted = !isEmojiCodePoint(cp) &&
            isTextDefaultSymbol(cp) &&
            i + width < text.length && text[i + width].code == 0xFE0F
        val part = isEmojiCodePoint(cp) || promoted ||
            (start >= 0 && isEmojiContinuation(cp))
        if (part) {
            if (start < 0) start = i
        } else if (start >= 0) {
            runs += start..(i - 1)
            start = -1
        }
        i += width
    }
    if (start >= 0) runs += start..(text.length - 1)
    return runs
}

private fun isEmojiCodePoint(cp: Int): Boolean = when (cp) {
    in 0x1F300..0x1FAFF -> true   // pictographs, emoticons, transport, supplemental, extended-A
    in 0x1F000..0x1F02F -> true   // mahjong, dominoes
    in 0x1F0A0..0x1F0FF -> true   // playing cards
    in 0x1F900..0x1F9FF -> true   // supplemental symbols and pictographs
    in 0x2600..0x26FF -> true     // miscellaneous symbols
    in 0x2700..0x27BF -> true     // dingbats
    in 0x1F1E6..0x1F1FF -> true   // regional indicators (flags)
    else -> false
}

/** Joiners and modifiers that extend an emoji run without starting one. */
private fun isEmojiContinuation(cp: Int): Boolean = when (cp) {
    0x200D -> true                // zero-width joiner
    0xFE0F, 0xFE0E -> true        // variation selectors
    0x20E3 -> true                // combining enclosing keycap
    in 0x1F3FB..0x1F3FF -> true   // skin-tone modifiers
    in 0xE0020..0xE007F -> true   // tag characters (subdivision flags)
    else -> false
}

/**
 * Code points that render as text by default and only become emoji when followed by U+FE0F —
 * arrows, digits, punctuation. Matching these unconditionally would restyle ordinary text.
 */
private fun isTextDefaultSymbol(cp: Int): Boolean = when (cp) {
    in 0x0023..0x0023, in 0x002A..0x002A, in 0x0030..0x0039 -> true // # * 0-9 (keycap bases)
    in 0x2190..0x21FF -> true     // arrows
    in 0x2B00..0x2BFF -> true     // miscellaneous symbols and arrows
    in 0x2000..0x206F -> true     // general punctuation
    in 0x3297..0x3299 -> true     // circled ideographs
    0x00A9, 0x00AE -> true        // copyright, registered
    else -> false
}
