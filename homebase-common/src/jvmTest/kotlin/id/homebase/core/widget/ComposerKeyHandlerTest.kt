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
        onEditLast: (() -> Boolean)? = { calls += "editLast"; true },
        imagePasted: Boolean = false,
    ): Boolean = handleComposerKey(
        isImeComposing = imeComposing,
        claimedByAutocomplete = { calls += "autocomplete"; autocompleteClaims },
        action = action,
        onSend = { calls += "send" },
        onNewline = onNewline,
        onEditLast = onEditLast,
        pasteImage = { calls += "paste"; imagePasted },
    )

    @Test
    fun `an open suggestion list wins enter and up over every composer intent`() {
        for (action in listOf(ComposerKeyAction.Send, ComposerKeyAction.Newline, ComposerKeyAction.EditLast)) {
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
        assertFalse(handle(imeComposing = true, action = ComposerKeyAction.EditLast))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `edit last claims the key once autocomplete passes`() {
        assertTrue(handle(action = ComposerKeyAction.EditLast))
        assertEquals(listOf("autocomplete", "editLast"), calls)
    }

    @Test
    fun `nothing to edit lets the caret move`() {
        assertFalse(handle(action = ComposerKeyAction.EditLast, onEditLast = { calls += "editLast"; false }))
        assertEquals(listOf("autocomplete", "editLast", "paste"), calls)

        calls.clear()
        assertFalse(handle(action = ComposerKeyAction.EditLast, onEditLast = null))
        assertEquals(listOf("autocomplete", "paste"), calls)
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
