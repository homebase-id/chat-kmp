package id.homebase.chat.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

internal fun highlightCode(code: String, language: String?, darkTheme: Boolean): AnnotatedString {
    val tag = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return AnnotatedString(code)
    val syntax = SyntaxLanguage.getByName(tag)
    if (syntax == null || syntax == SyntaxLanguage.DEFAULT) return AnnotatedString(code)
    val highlights = Highlights.Builder()
        .code(code)
        .language(syntax)
        .theme(SyntaxThemes.darcula(darkTheme))
        .build()
        .getHighlights()
    return buildAnnotatedString {
        append(code)
        highlights.forEach { h ->
            val start = h.location.start.coerceIn(0, code.length)
            val end = h.location.end.coerceIn(start, code.length)
            when (h) {
                is ColorHighlight -> addStyle(SpanStyle(color = Color(h.rgb or OPAQUE)), start, end)
                is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            }
        }
    }
}

private const val OPAQUE = 0xFF000000.toInt()
