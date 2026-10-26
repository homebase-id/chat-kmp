package id.homebase.core.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.core.ui.theme.LightColors

fun RichTextState.programmaticBackspace() {
    val selection = this.selection
    val text = annotatedString.toString()
    if (selection.start == 0) return

    // 1. If text is selected, delete the selection and stop
    if (!selection.collapsed) {
        //state.removeText(selection.start, selection.end)
        removeSelectedText()
        return
    }

    val end = selection.start
    var start = end

    // --- STEP 1: Move back over the first "unit" ---
    start = findPrecedingCharacterStart(text, start)

    // --- STEP 2: Check if what we just passed is a Modifier or Joiner ---
    // If it is, we need to keep moving 'start' backwards to include the base emoji
    while (start > 0) {
        val lastCodePoint = getCodePointAt(text, start)
        val prevCodePoint = getCodePointAt(text, findPrecedingCharacterStart(text, start))

        start = if (isEmojiModifier(lastCodePoint) || isZWJ(lastCodePoint) || isVariationSelector(lastCodePoint)) {
            findPrecedingCharacterStart(text, start)
        } else if (isZWJ(prevCodePoint)) {
            // If there's a joiner behind us, we MUST grab the character behind that too
            findPrecedingCharacterStart(text, start)
        } else {
            break
        }
    }

    removeTextRange(TextRange(start = start, end = end))
}

/**
 * richeditor serialises an empty paragraph as a standalone `<br>` line — its WYSIWYG empty-line
 * marker. So a composer holding a stray blank line round-trips through [RichTextState.toMarkdown]
 * as `"\n<br>"`, and a link with an empty line above it as `"\n<br>\nhttps://…"` (both byte-verified
 * against rc14). That `<br>` is editor noise, not content: it renders as a literal `<br>` on the web
 * client and a blank line on mobile, and a pure-`<br>` body reads as an empty message (#1104).
 *
 * This drops standalone `<br>` lines and the leading/trailing blank lines they leave behind, while
 * preserving real text and intentional paragraph breaks (an intended blank line between paragraphs
 * serialises as plain `\n\n`, never `<br>`, so it survives untouched). Lines inside a fenced code
 * block are literal content and are never dropped.
 */
fun String.stripComposerLineBreakArtifacts(): String =
    lines()
        .filterInFence { it.trim() != "<br>" }
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
        .joinToString("\n")

private fun List<String>.filterInFence(keep: (String) -> Boolean): List<String> {
    var fence: String? = null
    return filter { line ->
        val marker = line.trimStart().let { t ->
            when {
                t.startsWith("```") -> "```"
                t.startsWith("~~~") -> "~~~"
                else -> null
            }
        }
        when {
            fence == null && marker != null -> { fence = marker; true }
            fence != null && marker == fence -> { fence = null; true }
            fence != null -> true
            else -> keep(line)
        }
    }
}

/**
 * The composer's markdown body as it should be sent: [RichTextState.toMarkdown] with richeditor's
 * `<br>` empty-paragraph artifacts stripped (see [stripComposerLineBreakArtifacts]). Use this — not
 * raw `toMarkdown()` — for every composer send and send-gate decision so a stray blank line can
 * never go out as a `<br>`/blank message (#1104).
 */
fun RichTextState.toMessageMarkdown(): String = toMarkdown().stripComposerLineBreakArtifacts()

fun RichTextState.applyDefaultStyling(
    linkColor: Color = LightColors.Primary,
): RichTextState {
    return this.apply {
        config.listIndent = 20
        config.linkColor = linkColor
        config.linkTextDecoration = TextDecoration.Underline
    }
}

/**
 * Loads existing markdown INTO a richeditor [RichTextState] (the WYSIWYG editor).
 *
 * Read-only display no longer goes through here — chat bubbles, captions, the
 * conversation-list preview and the search row now render with the mikepenz
 * CommonMark renderer (`ChatMarkdown`), which does not need this try/catch +
 * [fixProblematicMarkdownText] safety net. The one remaining caller is the
 * editor-load path: seeding the composer / the Vault note editor with a
 * previously-saved markdown body, where richeditor's `setMarkdown` still has the
 * documented "space after a blank line" fragility. Keep this wrapper for that
 * path; it is not dead code.
 */
fun RichTextState.applyMarkDownContent(
    content: String,
): RichTextState {
    return this.apply {
        try {
            setMarkdown(content)
            // setMarkdown silently drops every block construct it can't render inline (a code
            // fence loses its markers, a rule vanishes) and toMarkdown() would persist that loss
            // on the next save, so anything it can't round-trip is edited as raw source instead.
            if (toMarkdown() != content) editAsRawMarkdown(content)
        } catch (e: Exception) {
            Logger.e(tag = "RichTextExtensions") { "setMarkdown failed, preserving raw text: $e" }
            editAsRawMarkdown(content)
        }
    }
}

// clear() is load-bearing: setText redistributes the source across whatever paragraphs and spans
// the failed setMarkdown left behind, scrambling the bytes and keeping its code-block box drawn.
private fun RichTextState.editAsRawMarkdown(content: String) {
    clear()
    setText(content)
}

private fun findPrecedingCharacterStart(text: String, offset: Int): Int {
    if (offset <= 0) return 0
    var newOffset = offset - 1
    // Handle UTF-16 surrogate pairs
    if (newOffset > 0 && text[newOffset].isLowSurrogate() && text[newOffset - 1].isHighSurrogate()) {
        newOffset--
    }
    return newOffset
}

private fun getCodePointAt(text: String, index: Int): Int {
    if (index < 0 || index >= text.length) return -1
    return if (text[index].isHighSurrogate() && index + 1 < text.length) {
        // Standard formula for converting surrogate pairs to a single Int CodePoint
        val high = text[index].code
        val low = text[index + 1].code
        0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
    } else {
        text[index].code
    }
}

// Logic for Emoji Skin Tone Modifiers (Fitzpatrick scale)
private fun isEmojiModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF
private fun isZWJ(cp: Int): Boolean = cp == 0x200D
private fun isVariationSelector(cp: Int): Boolean = cp in 0xFE00..0xFE0F

val URL_REGEX = Regex(
    "https?://(?:www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b(?:[-a-zA-Z0-9()@:%_+.~#?&/=]*)"
)

data class LinkAtSelection(val text: String, val url: String)

fun RichTextState.linkAtSelection(): LinkAtSelection? = withLinkProbe {
    val url = selectedLinkUrl ?: return@withLinkProbe null
    LinkAtSelection(text = selectedLinkText.orEmpty(), url = url)
}

fun RichTextState.updateLinkAtSelection(url: String) = withLinkProbe { updateLink(url) }

fun RichTextState.removeLinkAtSelection() = withLinkProbe { removeLink() }

// rc14 reports no link for a caret just past a span's last character, so probe the character before it, then restore.
// Every selection write drops the caret's pending bold/italic, so only move it when a link is really there.
@OptIn(ExperimentalRichTextApi::class)
private fun <T> RichTextState.withLinkProbe(block: RichTextState.() -> T?): T? {
    if (isLink) return block()
    val original = selection
    if (!original.collapsed || original.start == 0) return null
    val probe = TextRange(original.start - 1, original.start)
    if (getRichSpanStyle(probe) !is RichSpanStyle.Link) return null
    selection = probe
    try {
        return if (isLink) block() else null
    } finally {
        selection = original
    }
}

fun RichTextState.addLinkReplacingSelection(text: String, url: String) {
    if (!selection.collapsed) removeSelectedText()
    addLink(text = text, url = url)
}

fun RichTextState.selectedPlainText(): String =
    annotatedString.text.substring(selection.min, selection.max)

fun RichTextState.pasteUrlOverSelection(clipboardText: String?): Boolean {
    if (selection.collapsed) return false
    val url = clipboardText?.trim()?.takeIf { URL_REGEX.matchEntire(it) != null } ?: return false
    val label = selectedPlainText()
    if (label.isBlank()) return false
    removeSelectedText()
    addLink(text = label, url = url)
    return true
}

suspend fun RichTextState.pasteOverSelectionAsync(readText: suspend () -> String?) {
    val text = readText() ?: return
    if (pasteUrlOverSelection(text)) return
    if (!selection.collapsed) removeSelectedText()
    addTextAfterSelection(text)
}

fun HeadingStyle.nextInComposerCycle(): HeadingStyle = when (this) {
    HeadingStyle.Normal -> HeadingStyle.H1
    HeadingStyle.H1 -> HeadingStyle.H2
    HeadingStyle.H2 -> HeadingStyle.H3
    else -> HeadingStyle.Normal
}
