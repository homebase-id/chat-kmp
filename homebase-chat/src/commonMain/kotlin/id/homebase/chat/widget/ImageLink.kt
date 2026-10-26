package id.homebase.chat.widget

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode

internal class ImageLink(val label: String, val url: String)

internal fun imageLinkOf(content: String, node: ASTNode): ImageLink? {
    if (node.type != MarkdownElementTypes.IMAGE) return null
    val link = node.findChildOfType(MarkdownElementTypes.INLINE_LINK) ?: return null
    val destination = link.findChildOfType(MarkdownElementTypes.LINK_DESTINATION) ?: return null
    val url = destination.getTextInNode(content).toString().removeSurrounding("<", ">").trim()
    if (url.isEmpty()) return null
    val text = link.findChildOfType(MarkdownElementTypes.LINK_TEXT)
        ?.getTextInNode(content)?.toString()
        ?.removeSurrounding("[", "]")?.trim()
        .orEmpty()
    return ImageLink(label = text.ifEmpty { url }, url = url)
}

internal fun ImageLink.toAnnotated(styles: TextLinkStyles): AnnotatedString =
    AnnotatedString.Builder().apply {
        withLink(LinkAnnotation.Url(url, styles)) { append(label) }
    }.toAnnotatedString()
