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

    private fun key(
        ctrlOrCmd: Boolean = true,
        shift: Boolean = false,
        keyDown: Boolean = true,
        k: Boolean = false,
        b: Boolean = false,
        i: Boolean = false,
        e: Boolean = false,
        x: Boolean = false,
        y: Boolean = false,
        seven: Boolean = false,
        eight: Boolean = false,
        escape: Boolean = false,
        alt: Boolean = false,
    ) = ComposerKeyChord(
        isEnter = false,
        isArrowUp = false,
        isKeyDown = keyDown,
        isShiftPressed = shift,
        isCtrlOrCmdPressed = ctrlOrCmd,
        isK = k,
        isB = b,
        isI = i,
        isE = e,
        isX = x,
        isY = y,
        isSeven = seven,
        isEight = eight,
        isEscape = escape,
        isAltPressed = alt,
    )

    @Test
    fun `every formatting shortcut maps to its action`() {
        val table = listOf(
            key(b = true) to ComposerKeyAction.Bold,
            key(i = true) to ComposerKeyAction.Italic,
            key(e = true) to ComposerKeyAction.InlineCode,
            key(x = true, shift = true) to ComposerKeyAction.Strike,
            key(eight = true, shift = true) to ComposerKeyAction.BulletList,
            key(seven = true, shift = true) to ComposerKeyAction.NumberedList,
            key(y = true) to ComposerKeyAction.Redo,
        )
        for ((chord, expected) in table) {
            assertEquals(expected, action(chord), "$expected")
        }
    }

    @Test
    fun `formatting shortcuts work where plain enter is inert`() {
        assertEquals(ComposerKeyAction.Bold, action(key(b = true), handlesHardwareKeys = false))
        assertEquals(ComposerKeyAction.Ignore, action(chord(), handlesHardwareKeys = false))
    }

    @Test
    fun `an AltGr chord types its character instead of formatting`() {
        assertEquals(ComposerKeyAction.Ignore, action(key(e = true, alt = true)))
        assertEquals(ComposerKeyAction.Ignore, action(key(x = true, shift = true, alt = true)))
        assertEquals(ComposerKeyAction.Ignore, action(key(b = true, alt = true)))
        assertEquals(ComposerKeyAction.Link, action(key(k = true)))
        assertEquals(ComposerKeyAction.Ignore, action(key(k = true, alt = true)))
    }

    @Test
    fun `a key up or an unmodified letter is ignored`() {
        assertEquals(ComposerKeyAction.Ignore, action(key(b = true, keyDown = false)))
        assertEquals(ComposerKeyAction.Ignore, action(key(b = true, ctrlOrCmd = false)))
        assertEquals(ComposerKeyAction.Ignore, action(key(x = true, ctrlOrCmd = false, shift = true)))
        assertEquals(ComposerKeyAction.Ignore, action(key(eight = true, ctrlOrCmd = false, shift = true)))
    }

    @Test
    fun `shift changes which shortcut a digit or letter is`() {
        assertEquals(ComposerKeyAction.Ignore, action(key(x = true)))
        assertEquals(ComposerKeyAction.Ignore, action(key(eight = true)))
        assertEquals(ComposerKeyAction.Ignore, action(key(b = true, shift = true)))
        assertEquals(ComposerKeyAction.Ignore, action(key(y = true, shift = true)))
    }

    @Test
    fun `escape maps to dismiss trigger without a modifier`() {
        assertEquals(ComposerKeyAction.DismissTrigger, action(key(ctrlOrCmd = false, escape = true)))
        assertEquals(ComposerKeyAction.Ignore, action(key(ctrlOrCmd = false, escape = true, keyDown = false)))
    }

    @Test
    fun `shift enter is a newline when enter sends`() {
        assertEquals(ComposerKeyAction.Newline, action(chord(shift = true), enterSends = true))
    }
}
