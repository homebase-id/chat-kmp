package id.homebase.chat.widget

import androidx.compose.ui.Alignment

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

fun resolveQuoteCardAlpha(isDarkTheme: Boolean): Float =
    if (isDarkTheme) 0.10f else 0.50f
