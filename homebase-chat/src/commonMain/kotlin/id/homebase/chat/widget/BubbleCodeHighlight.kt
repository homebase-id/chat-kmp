package id.homebase.chat.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme

internal fun highlightCode(code: String, language: String?, theme: SyntaxTheme): AnnotatedString {
    val tag = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return AnnotatedString(code)
    val syntax = SyntaxLanguage.getByName(tag)
    if (syntax == null || syntax == SyntaxLanguage.DEFAULT) return AnnotatedString(code)
    val highlights = Highlights.Builder()
        .code(code)
        .language(syntax)
        .theme(theme)
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

internal const val MIN_CODE_CONTRAST = 4.5f

internal class BubbleCodeStyle(val background: Color, val theme: SyntaxTheme)

internal fun bubbleCodeStyle(scheme: ColorScheme, content: Color): BubbleCodeStyle {
    val sent = content == scheme.onPrimary
    val bubble = if (sent) scheme.primary else scheme.surfaceContainerHigh
    val bg = content.copy(alpha = FENCED_CODE_BG_ALPHA).compositeOver(bubble)
    fun tone(accent: Color) = readable(accent, content, bg)
    val comment = tone(lerp(content, bg, 0.35f))
    val theme = SyntaxTheme(
        key = if (sent) "bubble-sent" else "bubble-received",
        code = content.toArgb(),
        keyword = tone(scheme.tertiary).toArgb(),
        string = tone(scheme.secondary).toArgb(),
        literal = tone(scheme.inversePrimary).toArgb(),
        comment = comment.toArgb(),
        metadata = tone(scheme.tertiaryContainer).toArgb(),
        multilineComment = comment.toArgb(),
        punctuation = content.toArgb(),
        mark = content.toArgb(),
    )
    return BubbleCodeStyle(bg, theme)
}

private fun readable(accent: Color, content: Color, bg: Color): Color {
    for (step in 0..10) {
        val c = lerp(accent, content, step / 10f).copy(alpha = 1f)
        if (contrastRatio(c, bg) >= MIN_CODE_CONTRAST) return c
    }
    return content.copy(alpha = 1f)
}

internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}
