package id.homebase.chat.conversationlist

import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.core.util.toMessageMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/**
 * Pins the send-time composer contract behind #1492: the composer must be blank for the WHOLE
 * duration of the send, not just after it returns. A send that outlives the conversation's
 * `DRAFT_IDLE_MS` idle debounce otherwise lets the debounce read the still-populated composer and
 * persist the just-sent text as a synced draft, which then restores on re-entry.
 */
class ComposerClearedForSendTest {

    private val body = "the long message that came back as a draft"

    @Test
    fun composerIsBlankForTheWholeSend() = runTest {
        val composer = RichTextState()
        composer.setMarkdown(body)

        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val send = async {
            composer.clearedForSend(body) {
                sendStarted.complete(Unit)
                releaseSend.await()
            }
        }

        sendStarted.await()
        assertEquals("", composer.toMessageMarkdown(), "composer still held the sent text mid-send")

        releaseSend.complete(Unit)
        send.await()
        assertEquals("", composer.toMessageMarkdown())
    }

    @Test
    fun aFailedSendPutsTheTextBack() = runTest {
        val composer = RichTextState()
        composer.setMarkdown(body)

        val failed = async {
            runCatching {
                composer.clearedForSend(body) { error("upload blew up") }
            }
        }.await()

        assertEquals(true, failed.isFailure)
        assertEquals(body, composer.toMessageMarkdown())
    }

    @Test
    fun aFailedSendDoesNotClobberWhatTheUserTypedMeanwhile() = runTest {
        val composer = RichTextState()
        composer.setMarkdown(body)

        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val send = async {
            runCatching {
                composer.clearedForSend(body) {
                    sendStarted.complete(Unit)
                    releaseSend.await()
                    error("upload blew up")
                }
            }
        }

        sendStarted.await()
        composer.setMarkdown("something else entirely")
        releaseSend.complete(Unit)
        send.await()

        assertEquals("something else entirely", composer.toMessageMarkdown())
    }
}
