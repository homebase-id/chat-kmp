package id.homebase.core.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerKeyHandlerTest {

    private val calls = mutableListOf<String>()

    private fun handle(
        imeComposing: Boolean = false,
        autocompleteClaims: Boolean = false,
        action: ComposerKeyAction = ComposerKeyAction.Ignore,
        onNewline: (() -> Unit)? = { calls += "newline" },
        imagePasted: Boolean = false,
    ): Boolean = handleComposerKey(
        isImeComposing = imeComposing,
        claimedByAutocomplete = { calls += "autocomplete"; autocompleteClaims },
        action = action,
        onSend = { calls += "send" },
        onNewline = onNewline,
        pasteImage = { calls += "paste"; imagePasted },
    )

    @Test
    fun `an open suggestion list wins enter over send and newline`() {
        for (action in listOf(ComposerKeyAction.Send, ComposerKeyAction.Newline)) {
            calls.clear()
            assertTrue(handle(autocompleteClaims = true, action = action))
            assertEquals(listOf("autocomplete"), calls, "$action")
        }
    }

    @Test
    fun `a composing IME leaves every key alone`() {
        assertFalse(
            handle(
                imeComposing = true,
                autocompleteClaims = true,
                action = ComposerKeyAction.Send,
                imagePasted = true,
            )
        )
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `send and newline claim the key once autocomplete passes`() {
        assertTrue(handle(action = ComposerKeyAction.Send))
        assertEquals(listOf("autocomplete", "send"), calls)

        calls.clear()
        assertTrue(handle(action = ComposerKeyAction.Newline))
        assertEquals(listOf("autocomplete", "newline"), calls)
    }

    @Test
    fun `a single-line field lets a newline fall through`() {
        assertFalse(handle(action = ComposerKeyAction.Newline, onNewline = null))
        assertEquals(listOf("autocomplete", "paste"), calls)
    }

    @Test
    fun `paste still runs where the enter chord is inert`() {
        assertTrue(handle(action = ComposerKeyAction.Ignore, imagePasted = true))
        assertEquals(listOf("autocomplete", "paste"), calls)
    }
}
