package id.homebase.core.emoji

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EmojiShortcodes {
    private var cached: Map<String, String>? = null

    suspend fun index(): Map<String, String> =
        cached ?: withContext(Dispatchers.Default) {
            EmojiParser.loadEmojiData().emojis
                .flatMap { emoji -> emoji.shortcodes.orEmpty().map { it to emoji.emoji } }
                .toMap()
        }.also { cached = it }
}

data class ShortcodeReplacement(
    val text: String,
    val cursor: Int,
    val start: Int,
    val emoji: String,
)

private fun Char.isShortcodeChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
        this == '_' || this == '+' || this == '-'

/**
 * The splice boundaries are always the two ASCII `:` of the token, never an arbitrary offset, so a
 * surrogate pair can't be cut in half — a surrogate is not [isShortcodeChar] and stops the scan.
 */
fun replaceEmojiShortcode(
    text: String,
    cursor: Int,
    shortcodes: Map<String, String>,
): ShortcodeReplacement? {
    if (cursor !in 1..text.length) return null
    val closing = cursor - 1
    if (text[closing] != ':') return null

    var tokenStart = closing
    while (tokenStart > 0 && text[tokenStart - 1].isShortcodeChar()) tokenStart--
    val opening = tokenStart - 1
    if (opening < 0 || text[opening] != ':' || tokenStart == closing) return null

    val emoji = shortcodes[text.substring(tokenStart, closing).lowercase()] ?: return null
    return ShortcodeReplacement(
        text = text.substring(0, opening) + emoji + text.substring(cursor),
        cursor = opening + emoji.length,
        start = opening,
        emoji = emoji,
    )
}

fun RichTextState.replaceCompletedEmojiShortcode(shortcodes: Map<String, String>) {
    val cursor = selection
    if (!cursor.collapsed) return
    val match = replaceEmojiShortcode(annotatedString.text, cursor.start, shortcodes) ?: return
    replaceTextRange(TextRange(match.start, cursor.start), match.emoji)
}

@Composable
fun EmojiShortcodeEffect(state: RichTextState) {
    // Keyed on the state, not its text: keying on text would cancel and restart the one-off index
    // load on every keystroke, so it could never finish while the user types.
    LaunchedEffect(state) {
        val shortcodes = EmojiShortcodes.index()
        snapshotFlow { state.annotatedString.text }
            .collect { state.replaceCompletedEmojiShortcode(shortcodes) }
    }
}
