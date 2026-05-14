package id.homebase.chat.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputBarAnimationContractsTest {

    // ── Cancel FAB visibility (left side, edit mode only) ──

    @Test
    fun cancelFab_visible_inEditMode() {
        assertTrue(shouldShowCancelFab(editExistingMode = true))
    }

    @Test
    fun cancelFab_hidden_outsideEditMode() {
        assertFalse(shouldShowCancelFab(editExistingMode = false))
    }

    // ── Reply preview visibility ──

    @Test
    fun replyPreview_visible_whenReplyPresent() {
        assertTrue(shouldShowReplyPreview(hasReply = true))
    }

    @Test
    fun replyPreview_hidden_whenNoReply() {
        assertFalse(shouldShowReplyPreview(hasReply = false))
    }

    // ── Edit label visibility ──

    @Test
    fun editLabel_visible_inEditMode() {
        assertTrue(shouldShowEditLabel(editExistingMode = true))
    }

    @Test
    fun editLabel_hidden_outsideEditMode() {
        assertFalse(shouldShowEditLabel(editExistingMode = false))
    }

    // ── Right FAB area visibility (recording hides it) ──

    @Test
    fun rightFabArea_visible_whenNotRecording() {
        assertTrue(shouldShowRightFabArea(isRecordingActive = false))
    }

    @Test
    fun rightFabArea_hidden_whenRecording() {
        assertFalse(shouldShowRightFabArea(isRecordingActive = true))
    }

    // ── Payload renderers visibility (hidden during recording) ──

    @Test
    fun payloadRenderers_visible_whenNotRecording() {
        assertTrue(shouldShowPayloadRenderers(isRecordingActive = false))
    }

    @Test
    fun payloadRenderers_hidden_whenRecording() {
        assertFalse(shouldShowPayloadRenderers(isRecordingActive = true))
    }

    // ── Recording overlay visibility ──

    @Test
    fun recordingOverlay_visible_whenRecording() {
        assertTrue(shouldShowRecordingOverlay(isRecordingActive = true))
    }

    @Test
    fun recordingOverlay_hidden_whenNotRecording() {
        assertFalse(shouldShowRecordingOverlay(isRecordingActive = false))
    }

    // ── Standalone FAB state (MessageInputBar showActionButtons=true path) ──

    @Test
    fun standaloneFab_confirm_inEditMode() {
        val state = resolveStandaloneFabState(
            editExistingMode = true,
            showSendButton = true,
            isRecordingActive = false,
        )
        assertEquals(StandaloneFabState.CONFIRM, state)
    }

    @Test
    fun standaloneFab_send_whenSendable() {
        val state = resolveStandaloneFabState(
            editExistingMode = false,
            showSendButton = true,
            isRecordingActive = false,
        )
        assertEquals(StandaloneFabState.SEND, state)
    }

    @Test
    fun standaloneFab_attach_whenEmpty() {
        val state = resolveStandaloneFabState(
            editExistingMode = false,
            showSendButton = false,
            isRecordingActive = false,
        )
        assertEquals(StandaloneFabState.ATTACH, state)
    }

    @Test
    fun standaloneFab_hidden_whenRecording() {
        val state = resolveStandaloneFabState(
            editExistingMode = false,
            showSendButton = false,
            isRecordingActive = true,
        )
        assertEquals(StandaloneFabState.RECORDING_SPACER, state)
    }

    @Test
    fun standaloneFab_hidden_whenEditAndRecording() {
        val state = resolveStandaloneFabState(
            editExistingMode = true,
            showSendButton = true,
            isRecordingActive = true,
        )
        assertEquals(StandaloneFabState.RECORDING_SPACER, state)
    }

    // ── Desktop RTE buttons visibility ──

    @Test
    fun desktopRteButtons_visible_whenNotRecording() {
        assertTrue(shouldShowDesktopRteButtons(isRecordingActive = false, isDesktop = true))
    }

    @Test
    fun desktopRteButtons_hidden_whenRecording() {
        assertFalse(shouldShowDesktopRteButtons(isRecordingActive = true, isDesktop = true))
    }

    @Test
    fun desktopRteButtons_hidden_onMobile() {
        assertFalse(shouldShowDesktopRteButtons(isRecordingActive = false, isDesktop = false))
    }

    // ── Animation duration constant ──

    @Test
    fun signalTransitionDuration_is150ms() {
        assertEquals(150, SIGNAL_TRANSITION_DURATION_MS)
    }

    // ── Quote card background alpha — resolves per theme (Signal signal_colorTransparent3) ──

    @Test
    fun quoteCardAlpha_lighter_inLightTheme() {
        val light = resolveQuoteCardAlpha(isDarkTheme = false)
        val dark = resolveQuoteCardAlpha(isDarkTheme = true)
        assertTrue(light > dark, "Light theme overlay should be more opaque than dark")
    }

    @Test
    fun quoteCardAlpha_dark_isSubtle() {
        val alpha = resolveQuoteCardAlpha(isDarkTheme = true)
        assertTrue(alpha in 0.05f..0.20f, "Dark overlay should be subtle, was $alpha")
    }

    @Test
    fun quoteCardAlpha_light_isVisible() {
        val alpha = resolveQuoteCardAlpha(isDarkTheme = false)
        assertTrue(alpha in 0.30f..0.70f, "Light overlay should be clearly visible, was $alpha")
    }
}
