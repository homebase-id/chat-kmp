package id.homebase.core.widget

import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerEnterChordTest {

    private fun chord(
        isEnter: Boolean = true,
        isArrowUp: Boolean = false,
        isKeyDown: Boolean = true,
        shift: Boolean = false,
        sendModifier: Boolean = false,
    ) = ComposerKeyChord(
        isEnter = isEnter,
        isArrowUp = isArrowUp,
        isKeyDown = isKeyDown,
        isShiftPressed = shift,
        isCtrlOrCmdPressed = sendModifier,
    )

    private fun up(shift: Boolean = false, ctrlOrCmd: Boolean = false, isKeyDown: Boolean = true) =
        chord(isEnter = false, isArrowUp = true, isKeyDown = isKeyDown, shift = shift, sendModifier = ctrlOrCmd)

    private fun action(
        chord: ComposerKeyChord,
        enterSends: Boolean = false,
        upEdits: Boolean = true,
        empty: Boolean = true,
        handlesHardwareKeys: Boolean = true,
    ) = composerKeyAction(
        chord,
        enterSendsMessage = enterSends,
        handlesHardwareKeys = handlesHardwareKeys,
        arrowUpEditsLastMessage = upEdits,
        isComposerEmpty = { empty },
    )

    @Test
    fun `bare enter breaks the line by default`() {
        assertEquals(ComposerKeyAction.Newline, action(chord()))
    }

    @Test
    fun `shift enter sends by default`() {
        assertEquals(ComposerKeyAction.Send, action(chord(shift = true)))
    }

    @Test
    fun `bare enter sends once the preference is on`() {
        assertEquals(ComposerKeyAction.Send, action(chord(), enterSends = true))
    }

    @Test
    fun `shift enter breaks the line once the preference is on`() {
        assertEquals(ComposerKeyAction.Newline, action(chord(shift = true), enterSends = true))
    }

    @Test
    fun `ctrl or cmd enter sends in both modes`() {
        assertEquals(ComposerKeyAction.Send, action(chord(sendModifier = true)))
        assertEquals(ComposerKeyAction.Send, action(chord(sendModifier = true), enterSends = true))
        assertEquals(
            ComposerKeyAction.Send,
            action(chord(shift = true, sendModifier = true), enterSends = true),
        )
    }

    @Test
    fun `key up and other keys are ignored`() {
        assertEquals(ComposerKeyAction.Ignore, action(chord(isKeyDown = false)))
        assertEquals(ComposerKeyAction.Ignore, action(chord(isEnter = false)))
    }

    @Test
    fun `enter ignores the composer being empty and the arrow up preference`() {
        for (upEdits in listOf(false, true)) {
            for (empty in listOf(false, true)) {
                assertEquals(ComposerKeyAction.Newline, action(chord(), upEdits = upEdits, empty = empty))
                assertEquals(ComposerKeyAction.Send, action(chord(shift = true), upEdits = upEdits, empty = empty))
                assertEquals(
                    ComposerKeyAction.Send,
                    action(chord(sendModifier = true), upEdits = upEdits, empty = empty),
                )
            }
        }
    }

    @Test
    fun `ctrl or cmd up edits the last message even with text or the preference off`() {
        for (upEdits in listOf(false, true)) {
            for (empty in listOf(false, true)) {
                assertEquals(
                    ComposerKeyAction.EditLast,
                    action(up(ctrlOrCmd = true), upEdits = upEdits, empty = empty),
                    "upEdits=$upEdits empty=$empty",
                )
            }
        }
    }

    @Test
    fun `bare up edits only an empty composer with the preference on`() {
        assertEquals(ComposerKeyAction.EditLast, action(up()))
        assertEquals(ComposerKeyAction.Ignore, action(up(), empty = false))
        assertEquals(ComposerKeyAction.Ignore, action(up(), upEdits = false))
    }

    @Test
    fun `shift up keeps selecting text`() {
        assertEquals(ComposerKeyAction.Ignore, action(up(shift = true)))
        assertEquals(ComposerKeyAction.Ignore, action(up(shift = true, ctrlOrCmd = true)))
    }

    @Test
    fun `up key release is ignored`() {
        assertEquals(ComposerKeyAction.Ignore, action(up(isKeyDown = false)))
        assertEquals(ComposerKeyAction.Ignore, action(up(ctrlOrCmd = true, isKeyDown = false)))
    }

    @Test
    fun `mobile never edits from the arrow key`() {
        assertEquals(ComposerKeyAction.Ignore, action(up(), handlesHardwareKeys = false))
        assertEquals(ComposerKeyAction.Ignore, action(up(ctrlOrCmd = true), handlesHardwareKeys = false))
    }

    @Test
    fun `mobile leaves enter to the IME`() {
        for (enterSends in listOf(false, true)) {
            assertEquals(
                ComposerKeyAction.Ignore,
                action(chord(), enterSends, handlesHardwareKeys = false),
            )
            assertEquals(
                ComposerKeyAction.Ignore,
                action(chord(shift = true), enterSends, handlesHardwareKeys = false),
            )
        }
    }
}
