package id.homebase.core.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.trigger.Trigger
import id.homebase.core.emoji.Emoji
import id.homebase.core.emoji.EmojiParser
import id.homebase.core.emoji.EmojiShortcodeMaxSuggestions
import id.homebase.core.emoji.rankEmojiForShortcode
import id.homebase.core.ui.theme.withEmojiFont
import kotlin.math.roundToInt

private const val TriggerId = "emoji-shortcode"

/**
 * Owns the arrow/Enter/Escape keys while the shortcode popup is showing.
 *
 * The popup can't install richeditor's own `triggerKeyHandler` (it's internal), and the
 * composer's Enter-to-send lives in an `onPreviewKeyEvent` on the editor's outer modifier —
 * preview events run root-to-leaf, so that handler sees Enter before the editor does.
 * Without giving this first refusal there, Enter sends `:sm` as a message instead of
 * committing the highlighted emoji.
 */
@Stable
class EmojiShortcodeController internal constructor() {
    internal var keyHandler: ((KeyEvent) -> Boolean)? by mutableStateOf(null)
    internal var anchorBounds: Rect? by mutableStateOf(null)

    val isActive: Boolean get() = keyHandler != null

    fun handleKeyEvent(event: KeyEvent): Boolean = keyHandler?.invoke(event) ?: false
}

@Composable
fun rememberEmojiShortcodeController(): EmojiShortcodeController =
    remember { EmojiShortcodeController() }

/** Marks the editor the popup should sit above. Attach to the editor's own modifier chain. */
fun Modifier.emojiShortcodeAnchor(controller: EmojiShortcodeController): Modifier =
    onGloballyPositioned { coords ->
        val bounds = coords.boundsInWindow()
        if (controller.anchorBounds != bounds) controller.anchorBounds = bounds
    }

/**
 * Types-`:`-to-pick-an-emoji for a [RichTextState] editor.
 *
 * Attach [emojiShortcodeAnchor] to the editor, route the editor's `onPreviewKeyEvent`
 * through [EmojiShortcodeController.handleKeyEvent] first, then place this anywhere in
 * the same composition.
 *
 * Pass the editor's focus state as [enabled]: an active query survives focus loss (it is
 * recomputed only on text and selection changes), so without this the popup hangs around
 * after the user clicks away. Unregistering also clears any query already in flight.
 */
@OptIn(ExperimentalRichTextApi::class)
@Composable
fun EmojiShortcodePopup(
    state: RichTextState,
    controller: EmojiShortcodeController,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxSuggestions: Int = EmojiShortcodeMaxSuggestions,
) {
    if (!enabled) return

    DisposableEffect(state) {
        state.registerTrigger(Trigger(id = TriggerId, char = ':', maxQueryLength = 32))
        onDispose { state.unregisterTrigger(TriggerId) }
    }

    val query = state.activeTriggerQuery?.takeIf { it.triggerId == TriggerId }

    // 775 KB of JSON. Parse it on the first `:` rather than on every conversation open;
    // EmojiParser caches, so this popup and the emoji sheet pay for it once between them.
    var emojis by remember { mutableStateOf<List<Emoji>?>(null) }
    LaunchedEffect(query != null) {
        if (query != null && emojis == null) {
            emojis = runCatching { EmojiParser.loadEmojiData().emojis }.getOrNull()
        }
    }

    val available = emojis
    val suggestions = remember(query?.query, available, maxSuggestions) {
        if (query == null || available == null) emptyList()
        else rankEmojiForShortcode(query.query, available, maxSuggestions)
    }

    val anchor = controller.anchorBounds
    if (query == null || suggestions.isEmpty() || anchor == null) return

    SuggestionPopup(
        state = state,
        controller = controller,
        anchor = anchor,
        range = query.range,
        suggestions = suggestions,
        modifier = modifier,
    )
}

@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun SuggestionPopup(
    state: RichTextState,
    controller: EmojiShortcodeController,
    anchor: Rect,
    range: TextRange,
    suggestions: List<Emoji>,
    modifier: Modifier,
) {
    var highlighted by remember(suggestions) { mutableStateOf(0) }
    val safeHighlighted = highlighted.coerceIn(0, suggestions.lastIndex)

    // insertToken would splice an atomic styled Token span — mention semantics that
    // round-trip as a token through markdown. The composer ships toMessageMarkdown(),
    // so the emoji has to land as literal text.
    fun commit(emoji: Emoji) = state.replaceTextRange(range, emoji.emoji)

    DisposableEffect(controller, suggestions, range) {
        controller.keyHandler = handler@{ event ->
            if (event.type != KeyEventType.KeyDown) return@handler false
            when (event.key) {
                Key.DirectionDown -> {
                    highlighted = (highlighted + 1).mod(suggestions.size)
                    true
                }

                Key.DirectionUp -> {
                    highlighted = (highlighted - 1 + suggestions.size).mod(suggestions.size)
                    true
                }

                Key.Enter, Key.NumPadEnter, Key.Tab -> {
                    suggestions.getOrNull(highlighted.coerceIn(0, suggestions.lastIndex))
                        ?.let(::commit)
                    true
                }

                Key.Escape -> {
                    state.cancelActiveTrigger()
                    true
                }

                else -> false
            }
        }
        onDispose { controller.keyHandler = null }
    }

    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    val positionProvider = remember(anchor, gapPx) { AboveAnchorPositionProvider(anchor, gapPx) }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { state.cancelActiveTrigger() },
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = modifier.widthIn(min = 200.dp, max = 320.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                suggestions.forEachIndexed { index, emoji ->
                    SuggestionRow(
                        emoji = emoji,
                        highlighted = index == safeHighlighted,
                        onClick = { commit(emoji) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    emoji: Emoji,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = emoji.emoji.withEmojiFont(),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = emoji.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Anchors to the editor's bounds rather than the caret: richeditor keeps both the text
 * field's window position and its TextLayoutResult internal, and the caret rect that *is*
 * public is relative to the inner field — offset from the decoration box by the leading
 * icon. A composer-width strip above the input is the honest, stable placement.
 *
 * [anchorBounds] is ignored: the popup is not a child of the editor, so its own parent
 * bounds say nothing useful. [anchor] is the editor's rect in window coordinates.
 */
private class AboveAnchorPositionProvider(
    private val anchor: Rect,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchor.left.roundToInt().coerceIn(0, maxX)

        val above = anchor.top.roundToInt() - gapPx - popupContentSize.height
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val y = if (above >= 0) above else (anchor.bottom.roundToInt() + gapPx).coerceIn(0, maxY)

        return IntOffset(x, y)
    }
}
