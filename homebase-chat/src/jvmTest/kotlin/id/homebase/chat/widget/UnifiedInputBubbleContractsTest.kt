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
