package id.homebase.chat.conversationlist

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Regression guard for the reported "infinity spinner" in chat details.
 *
 * loadMessagesForConversation arms the spinner (isLoadingMessages=true) and the success
 * path clears it inside the observeMessages collect. A `finally` backstop clears it for the
 * paths the success path never reaches — an error, an unexpected cancellation, or a hung
 * load — so the detail pane can't spin forever. [shouldClearLoadingSpinnerOnLoadEnd] encodes
 * exactly when that backstop should fire; these tests lock the rule down.
 */
class SpinnerBackstopGuardTest {

    private val convoA = Uuid.random()
    private val convoB = Uuid.random()

    @Test
    fun clears_whenStillSelectedAndStillLoading() {
        // The bug case: the load ended (e.g. threw) while we're still on this conversation
        // with the spinner up — the backstop MUST clear it.
        assertTrue(
            shouldClearLoadingSpinnerOnLoadEnd(
                selectedConversationId = convoA,
                conversationId = convoA,
                stillLoading = true,
            )
        )
    }

    @Test
    fun doesNotClear_whenAlreadyCleared() {
        // Normal success: the collect already cleared the spinner — nothing to do.
        assertFalse(
            shouldClearLoadingSpinnerOnLoadEnd(
                selectedConversationId = convoA,
                conversationId = convoA,
                stillLoading = false,
            )
        )
    }

    @Test
    fun doesNotClear_whenSwitchedToAnotherConversation() {
        // Cancellation from switching conversations: the new selection already armed the
        // spinner for convoB. The cancelled convoA job must NOT wipe convoB's spinner.
        assertFalse(
            shouldClearLoadingSpinnerOnLoadEnd(
                selectedConversationId = convoB,
                conversationId = convoA,
                stillLoading = true,
            )
        )
    }

    @Test
    fun doesNotClear_whenNothingSelected() {
        assertFalse(
            shouldClearLoadingSpinnerOnLoadEnd(
                selectedConversationId = null,
                conversationId = convoA,
                stillLoading = true,
            )
        )
    }
}
