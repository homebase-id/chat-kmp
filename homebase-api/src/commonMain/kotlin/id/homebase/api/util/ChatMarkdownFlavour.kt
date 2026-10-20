package id.homebase.api.util

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.lexer._GFMLexer
import org.intellij.markdown.lexer.GeneratedLexer
import org.intellij.markdown.lexer.MarkdownLexer

/**
 * GFM with `$` as plain text. Chat has no maths: GFM turns `$…$` into a math node the renderer
 * never draws, and mikepenz renders a `$$` token as one `$`. Every chat markdown parse uses this.
 */
object ChatMarkdownFlavour : GFMFlavourDescriptor() {
    override fun createInlinesLexer(): MarkdownLexer = MarkdownLexer(LiteralDollarLexer(_GFMLexer()))
}

private class LiteralDollarLexer(private val gfm: GeneratedLexer) : GeneratedLexer by gfm {
    override fun advance(): IElementType? =
        gfm.advance().let { if (it == GFMTokenTypes.DOLLAR) MarkdownTokenTypes.TEXT else it }
}
