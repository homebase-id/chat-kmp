package id.homebase.core.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
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
import id.homebase.core.util.replaceTextRangeSafely

const val ComposerAutocompleteTag: String = "composer_autocomplete"

/**
 * Owns the arrow/Enter/Tab/Escape keys while a [ComposerAutocomplete] list is showing.
 *
 * richeditor's own `triggerKeyHandler` is internal, and the composer's Enter-to-send lives in an
 * `onPreviewKeyEvent` on the editor's outer modifier — preview events run root-to-leaf, so that
 * handler sees Enter before the editor does. Without giving this first refusal there, Enter sends
 * `:sm` as a message instead of committing the highlighted suggestion.
 */
@Stable
class ComposerAutocompleteController internal constructor() {
    internal var keyHandler: ((KeyEvent) -> Boolean)? by mutableStateOf(null)

    fun handleKeyEvent(event: KeyEvent): Boolean = keyHandler?.invoke(event) ?: false
}

@Composable
fun rememberComposerAutocompleteController(): ComposerAutocompleteController =
    remember { ComposerAutocompleteController() }

/**
 * Typeahead dropdown for a [RichTextState] composer: typing [triggerChar] opens a list, typing on
 * filters it, and picking an entry splices [replacementFor] over the trigger token.
 *
 * Place it as a sibling of the editor inside a `Box` that wraps ONLY the editor — that Box is the
 * anchor — and route the editor's `onPreviewKeyEvent` through [ComposerAutocompleteController]
 * first.
 *
 * The trigger detection comes from richeditor, which brings the word-boundary rule that keeps
 * `10:30` and `https://` from opening the list. Commit goes through [replacementFor] and
 * `replaceTextRange` rather than richeditor's `insertToken`: a token is an atomic styled span, and
 * the composer ships `toMessageMarkdown()`, so a replacement has to land as literal text.
 *
 * [suggestionsFor] is cancelled and restarted on every keystroke, so a remote lookup can debounce
 * itself with a leading `delay`.
 */
@OptIn(ExperimentalRichTextApi::class)
@Composable
fun <T> ComposerAutocomplete(
    state: RichTextState,
    controller: ComposerAutocompleteController,
    triggerId: String,
    triggerChar: Char,
    suggestionsFor: suspend (String) -> List<T>,
    replacementFor: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    itemContent: @Composable (item: T, selected: Boolean) -> Unit,
) {
    if (!enabled) return

    DisposableEffect(state, triggerId, triggerChar) {
        state.registerTrigger(Trigger(id = triggerId, char = triggerChar))
        onDispose { state.unregisterTrigger(triggerId) }
    }

    val query = state.activeTriggerQuery?.takeIf { it.triggerId == triggerId }
    var suggestions by remember { mutableStateOf(emptyList<T>()) }
    LaunchedEffect(query?.query) {
        suggestions = query?.let { suggestionsFor(it.query) }.orEmpty()
    }

    if (query == null || suggestions.isEmpty()) return

    SuggestionList(
        state = state,
        controller = controller,
        range = query.range,
        suggestions = suggestions,
        replacementFor = replacementFor,
        modifier = modifier,
        itemContent = itemContent,
    )
}

@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun <T> SuggestionList(
    state: RichTextState,
    controller: ComposerAutocompleteController,
    range: TextRange,
    suggestions: List<T>,
    replacementFor: (T) -> String,
    modifier: Modifier,
    itemContent: @Composable (item: T, selected: Boolean) -> Unit,
) {
    var selected by remember(suggestions) { mutableStateOf(0) }
    val safeSelected = selected.coerceIn(0, suggestions.lastIndex)

    val currentSuggestions by rememberUpdatedState(suggestions)
    val currentRange by rememberUpdatedState(range)
    val currentReplacement by rememberUpdatedState(replacementFor)

    fun commit(item: T) = state.replaceTextRangeSafely(currentRange, currentReplacement(item))

    DisposableEffect(controller) {
        controller.keyHandler = handler@{ event ->
            if (event.type != KeyEventType.KeyDown) return@handler false
            val items = currentSuggestions
            if (items.isEmpty()) return@handler false
            when (event.key) {
                Key.DirectionDown -> {
                    selected = (selected.coerceIn(0, items.lastIndex) + 1).mod(items.size)
                    true
                }

                Key.DirectionUp -> {
                    selected = (selected.coerceIn(0, items.lastIndex) - 1).mod(items.size)
                    true
                }

                // NumPadEnter too: macOS can report Return as NumPadEnter (#1043). Shift+Enter is
                // the composer's newline and stays the composer's, open list or not.
                Key.Enter, Key.NumPadEnter, Key.Tab -> {
                    if (event.isShiftPressed && event.key != Key.Tab) {
                        false
                    } else {
                        items.getOrNull(selected.coerceIn(0, items.lastIndex))?.let(::commit)
                        true
                    }
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
    val positionProvider = remember(gapPx) { AboveAnchorPositionProvider(gapPx) }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { state.cancelActiveTrigger() },
        // Focusable would pull the caret out of the editor; the editor keeps receiving keystrokes
        // and forwards the navigation keys through the controller instead.
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = modifier.testTag(ComposerAutocompleteTag).widthIn(min = 200.dp, max = 320.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                suggestions.forEachIndexed { index, item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (index == safeSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { commit(item) },
                    ) {
                        itemContent(item, index == safeSelected)
                    }
                }
            }
        }
    }
}

/**
 * Sits above the editor rather than at the caret: richeditor keeps the text field's window position
 * and its TextLayoutResult internal, and the caret rect that is public is relative to the inner
 * field, offset from the decoration box by the leading icon.
 *
 * [anchorBounds] is Compose's own measurement of the parent Box, handed over during placement.
 * Deriving the anchor any other way — `onGloballyPositioned` writing a state that composition then
 * reads — makes every layout pass invalidate composition, which re-lays out, which writes again;
 * the composer resizes the editor continuously while the mic button runs a 1000 ms animation, so
 * those bounds never settle.
 */
private class AboveAnchorPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchorBounds.left.coerceIn(0, maxX)

        val above = anchorBounds.top - gapPx - popupContentSize.height
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val y = if (above >= 0) above else (anchorBounds.bottom + gapPx).coerceIn(0, maxY)

        return IntOffset(x, y)
    }
}
