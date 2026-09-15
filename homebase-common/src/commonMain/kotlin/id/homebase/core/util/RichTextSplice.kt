package id.homebase.core.util

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.util.isSurrogatePairAt

/**
 * The single splice the inline `:shortcode:` replacement and the composer autocomplete commit both
 * go through. A range end that lands between the two halves of a surrogate pair would leave a lone
 * surrogate behind, so each end snaps outward to the enclosing code-point boundary first.
 */
fun RichTextState.replaceTextRangeSafely(range: TextRange, replacement: String) {
    replaceTextRange(annotatedString.text.codePointBoundedRange(range), replacement)
}

fun String.codePointBoundedRange(range: TextRange): TextRange {
    val start = range.min.coerceIn(0, length)
    val end = range.max.coerceIn(0, length)
    return TextRange(
        if (isSurrogatePairAt(start - 1)) start - 1 else start,
        if (isSurrogatePairAt(end - 1)) end + 1 else end,
    )
}
