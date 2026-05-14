package id.homebase.chat.widget

import androidx.compose.ui.Alignment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the behavioral contracts of the unified input bubble layout.
 *
 * These verify the alignment and visibility rules that the composables
 * implement, extracted as pure functions for testability.
 */
class UnifiedInputBubbleContractsTest {

    // ── FAB vertical alignment contract ──

    @Test
    fun fabAlignment_centerVertically_whenNoReplyAndNoEdit() {
        val alignment = resolveFabAlignment(
            editExistingMode = false,
            hasReply = false,
        )
        assertEquals(Alignment.CenterVertically, alignment)
    }

    @Test
    fun fabAlignment_bottom_whenEditMode() {
        val alignment = resolveFabAlignment(
            editExistingMode = true,
            hasReply = false,
        )
        assertEquals(Alignment.Bottom, alignment)
    }

    @Test
    fun fabAlignment_bottom_whenReplyActive() {
        val alignment = resolveFabAlignment(
            editExistingMode = false,
            hasReply = true,
        )
        assertEquals(Alignment.Bottom, alignment)
    }

    @Test
    fun fabAlignment_bottom_whenBothEditAndReply() {
        val alignment = resolveFabAlignment(
            editExistingMode = true,
            hasReply = true,
        )
        assertEquals(Alignment.Bottom, alignment)
    }

    // ── Right FAB icon selection contract ──

    @Test
    fun rightFab_showsCheck_inEditMode() {
        val state = resolveRightFabState(
            editExistingMode = true,
            showSendButton = false,
            isRecordingActive = false,
        )
        assertEquals(RightFabState.CONFIRM, state)
    }

    @Test
    fun rightFab_showsSend_whenSendable() {
        val state = resolveRightFabState(
            editExistingMode = false,
            showSendButton = true,
            isRecordingActive = false,
        )
        assertEquals(RightFabState.SEND, state)
    }

    @Test
    fun rightFab_showsAdd_whenEmptyAndNotEditing() {
        val state = resolveRightFabState(
            editExistingMode = false,
            showSendButton = false,
            isRecordingActive = false,
        )
        assertEquals(RightFabState.ADD, state)
    }

    @Test
    fun rightFab_hidden_whenRecording() {
        val state = resolveRightFabState(
            editExistingMode = false,
            showSendButton = true,
            isRecordingActive = true,
        )
        assertEquals(RightFabState.HIDDEN, state)
    }

    @Test
    fun rightFab_editTakesPrecedence_overSend() {
        val state = resolveRightFabState(
            editExistingMode = true,
            showSendButton = true,
            isRecordingActive = false,
        )
        assertEquals(RightFabState.CONFIRM, state)
    }

    // ── Compact text field padding contract ──

    @Test
    fun compactPadding_hasExternalPadding_inStandaloneMode() {
        val hasExternalPadding = shouldApplyStandalonePadding(showActionButtons = true)
        assertEquals(true, hasExternalPadding)
    }

    @Test
    fun compactPadding_noExternalPadding_insideUnifiedBubble() {
        val hasExternalPadding = shouldApplyStandalonePadding(showActionButtons = false)
        assertEquals(false, hasExternalPadding)
    }

    // ── Mic button sizing contract ──

    @Test
    fun micSize_large_inStandaloneMode_resting() {
        val size = resolveMicSizeDp(
            showActionButtons = true,
            isMicrophonePressed = false,
        )
        assertEquals(56, size)
    }

    @Test
    fun micSize_larger_inStandaloneMode_pressed() {
        val size = resolveMicSizeDp(
            showActionButtons = true,
            isMicrophonePressed = true,
        )
        assertEquals(72, size)
    }

    @Test
    fun micSize_compact_insideBubble_resting() {
        val size = resolveMicSizeDp(
            showActionButtons = false,
            isMicrophonePressed = false,
        )
        assertEquals(40, size)
    }

    @Test
    fun micSize_compact_insideBubble_pressed() {
        val size = resolveMicSizeDp(
            showActionButtons = false,
            isMicrophonePressed = true,
        )
        assertEquals(40, size)
    }

    // ── Recording spacer contract ──

    @Test
    fun recordingSpacer_shown_inStandaloneMode() {
        val showSpacer = shouldShowRecordingSpacer(
            isRecordingActive = true,
            showActionButtons = true,
        )
        assertEquals(true, showSpacer)
    }

    @Test
    fun recordingSpacer_hidden_insideBubble() {
        val showSpacer = shouldShowRecordingSpacer(
            isRecordingActive = true,
            showActionButtons = false,
        )
        assertEquals(false, showSpacer)
    }

    @Test
    fun recordingSpacer_hidden_whenNotRecording() {
        val showSpacer = shouldShowRecordingSpacer(
            isRecordingActive = false,
            showActionButtons = true,
        )
        assertEquals(false, showSpacer)
    }
}

// ── Pure functions extracted from composable logic for testability ──

const val SIGNAL_TRANSITION_DURATION_MS = 150

enum class RightFabState { CONFIRM, SEND, ADD, HIDDEN }

enum class StandaloneFabState { CONFIRM, SEND, ATTACH, RECORDING_SPACER }

fun resolveFabAlignment(
    editExistingMode: Boolean,
    hasReply: Boolean,
): Alignment.Vertical =
    if (editExistingMode || hasReply) Alignment.Bottom else Alignment.CenterVertically

fun resolveRightFabState(
    editExistingMode: Boolean,
    showSendButton: Boolean,
    isRecordingActive: Boolean,
): RightFabState = when {
    isRecordingActive -> RightFabState.HIDDEN
    editExistingMode -> RightFabState.CONFIRM
    showSendButton -> RightFabState.SEND
    else -> RightFabState.ADD
}

fun resolveStandaloneFabState(
    editExistingMode: Boolean,
    showSendButton: Boolean,
    isRecordingActive: Boolean,
): StandaloneFabState = when {
    isRecordingActive -> StandaloneFabState.RECORDING_SPACER
    editExistingMode -> StandaloneFabState.CONFIRM
    showSendButton -> StandaloneFabState.SEND
    else -> StandaloneFabState.ATTACH
}

fun shouldShowCancelFab(editExistingMode: Boolean): Boolean = editExistingMode

fun shouldShowReplyPreview(hasReply: Boolean): Boolean = hasReply

fun shouldShowEditLabel(editExistingMode: Boolean): Boolean = editExistingMode

fun shouldShowRightFabArea(isRecordingActive: Boolean): Boolean = !isRecordingActive

fun shouldShowPayloadRenderers(isRecordingActive: Boolean): Boolean = !isRecordingActive

fun shouldShowRecordingOverlay(isRecordingActive: Boolean): Boolean = isRecordingActive

fun shouldShowDesktopRteButtons(isRecordingActive: Boolean, isDesktop: Boolean): Boolean =
    isDesktop && !isRecordingActive

fun shouldApplyStandalonePadding(showActionButtons: Boolean): Boolean = showActionButtons

fun resolveMicSizeDp(
    showActionButtons: Boolean,
    isMicrophonePressed: Boolean,
): Int = if (showActionButtons) {
    if (isMicrophonePressed) 72 else 56
} else {
    40
}

fun shouldShowRecordingSpacer(
    isRecordingActive: Boolean,
    showActionButtons: Boolean,
): Boolean = isRecordingActive && showActionButtons

// ── Quote card background overlay (Signal signal_colorTransparent3) ──

fun resolveQuoteCardAlpha(isDarkTheme: Boolean): Float =
    if (isDarkTheme) 0.10f else 0.50f
