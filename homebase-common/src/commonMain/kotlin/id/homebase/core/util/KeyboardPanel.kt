package id.homebase.core.util

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val FallbackPanelHeight = 300.dp

// Longest a soft keyboard takes to finish rising; past it none is coming (hardware keyboard attached).
private const val KeyboardArrivalTimeoutMs = 600L

/**
 * One bottom-panel height shared by the soft keyboard and a keyboard-replacing panel
 * (emoji, attachments): `max(keyboard, revealed panel)`, so swapping between them keeps
 * the composer still.
 */
@Stable
class KeyboardPanelState internal constructor(
    internal val ime: State<ImeOffsetState>,
    private val windowSize: State<IntSize>,
    private val fallbackPx: Int,
    private val hasSoftKeyboard: Boolean,
) {
    private val keyboardPxByWindow = mutableStateMapOf<IntSize, Int>()
    internal val reveal = Animatable(0, Int.VectorConverter)
    internal var awaitingKeyboard by mutableStateOf(false)

    var isOpen by mutableStateOf(false)
        private set

    /** True while a field inside the panel owns the keyboard, which then sits under the panel. */
    var panelFocused by mutableStateOf(false)

    val heightPx: Int get() = keyboardPxByWindow[windowSize.value] ?: fallbackPx

    val isPanelComposed: Boolean by derivedStateOf { isOpen || reveal.value > 0 }

    private val keyboardUnderPanel: Boolean get() = panelFocused && isOpen

    /** Height the content above must give up. Read it in layout or draw only. */
    val contentInsetPx: Int
        get() = if (keyboardUnderPanel) keyboardPx + reveal.value else maxOf(keyboardPx, reveal.value)

    internal val panelTopPx: Int
        get() = contentInsetPx - reveal.value - if (keyboardUnderPanel) keyboardPx else 0

    fun open() {
        awaitingKeyboard = false
        isOpen = true
    }

    fun close() {
        isOpen = false
    }

    /** Closes the panel but keeps reserving its height until the rising keyboard covers it. */
    fun closeForKeyboard() {
        if (isOpen && hasSoftKeyboard) awaitingKeyboard = true
        isOpen = false
    }

    internal fun recordKeyboard(px: Int) {
        keyboardPxByWindow[windowSize.value] = px
    }

    internal val keyboardPx: Int get() = ime.value.pureImeBottomPx
}

@Composable
fun rememberKeyboardPanelState(
    imeOffsetState: ImeOffsetState = rememberImeOffsetState(),
    hasSoftKeyboard: Boolean = isMobile(),
): KeyboardPanelState {
    val ime = rememberUpdatedState(imeOffsetState)
    val windowSize = rememberUpdatedState(LocalWindowInfo.current.containerSize)
    val fallbackPx = with(LocalDensity.current) { FallbackPanelHeight.roundToPx() }
    val state = remember { KeyboardPanelState(ime, windowSize, fallbackPx, hasSoftKeyboard) }
    val spec by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Int>())

    LaunchedEffect(state) {
        launch {
            // The first non-zero IME frame is a few px into the slide; the peak of a show,
            // committed once the keyboard starts falling, is its settled height.
            var peak = 0
            snapshotFlow { state.keyboardPx }.collect { px ->
                if (px > peak) {
                    peak = px
                    if (peak > state.heightPx) state.recordKeyboard(peak)
                } else if (px < peak) {
                    state.recordKeyboard(peak)
                    if (px == 0) peak = 0
                }
            }
        }
        launch {
            snapshotFlow { state.awaitingKeyboard }.collect { awaiting ->
                if (!awaiting) return@collect
                withTimeoutOrNull(KeyboardArrivalTimeoutMs) {
                    snapshotFlow { state.keyboardPx >= state.heightPx }.first { it }
                }
                state.awaitingKeyboard = false
            }
        }
        snapshotFlow { if (state.isOpen || state.awaitingKeyboard) state.heightPx else 0 }
            .collectLatest { target ->
                // Replacing the keyboard happens behind it, so only a panel rising from nothing slides.
                if (state.keyboardPx > 0) state.reveal.snapTo(target)
                else state.reveal.animateTo(target, spec)
            }
    }
    return state
}

/** The space under the composer shared by the keyboard and the panel, revealing the panel from its bottom edge. */
// keyboardHandledByHost: the host already rose by the keyboard, so only the panel's excess is reserved.
fun Modifier.keyboardPanelSlot(state: KeyboardPanelState, keyboardHandledByHost: Boolean = false): Modifier =
    clipToBounds().onFocusChanged { state.panelFocused = it.hasFocus }.layout { measurable, constraints ->
        val panelHeight = state.heightPx
        val placeable = measurable.measure(constraints.copy(minHeight = panelHeight, maxHeight = panelHeight))
        val height = state.contentInsetPx - if (keyboardHandledByHost) state.keyboardPx else 0
        layout(placeable.width, height) { placeable.place(0, state.panelTopPx) }
    }

// For a composer at the foot of a zero-inset ModalBottomSheet, whose content M3 wraps in imePadding() on every
// platform; pair with keyboardPanelSlot(keyboardHandledByHost = true). [state] must be remembered outside the sheet.
fun Modifier.sheetComposerInset(state: KeyboardPanelState): Modifier = layout { measurable, constraints ->
    val ime = state.ime.value
    val nav = ime.navBarInsets.getBottom(ime.density)
    // Once the lifted sheet sits on the keyboard, the nav bar is under the keyboard too.
    val bottom = (nav - ime.imeBottomPx).coerceAtLeast(0)
    val placeable = measurable.measure(constraints.offset(vertical = -bottom))
    layout(placeable.width, placeable.height + bottom) { placeable.place(0, 0) }
}

/**
 * For a top-anchored list above a [keyboardPanelSlot]: keeps measuring the list at its
 * un-inset height and lifts it, so the newest rows stay above the composer without scrolling,
 * while a list shorter than the viewport only moves as far as its empty space runs out.
 */
fun Modifier.keyboardPanelAnchoredList(
    state: KeyboardPanelState,
    listState: LazyListState,
): Modifier = clipToBounds().layout { measurable, constraints ->
    val inset = state.contentInsetPx
    val fullHeight = constraints.maxHeight + inset
    val placeable = measurable.measure(constraints.copy(minHeight = fullHeight, maxHeight = fullHeight))
    layout(constraints.maxWidth, constraints.maxHeight) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        val emptyBelow = if (last != null && last.index == info.totalItemsCount - 1) {
            (info.viewportEndOffset - info.afterContentPadding - (last.offset + last.size)).coerceAtLeast(0)
        } else {
            0
        }
        placeable.place(0, -(inset - emptyBelow).coerceAtLeast(0))
    }
}
