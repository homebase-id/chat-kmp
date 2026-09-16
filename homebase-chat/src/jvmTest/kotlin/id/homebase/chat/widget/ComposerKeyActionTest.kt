package id.homebase.chat.widget

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The decision table the three composer fields share. It is the thing that drifted apart when the
 * same logic was written out three times.
 */
class ComposerKeyActionTest {

    private fun chord(
        isEnter: Boolean = true,
        isKeyDown: Boolean = true,
        shift: Boolean = false,
        sendModifier: Boolean = false,
        ime: Boolean = false,
    ) = ComposerEnterChord(
        isEnter = isEnter,
        isKeyDown = isKeyDown,
        isShiftPressed = shift,
        isSendModifierPressed = sendModifier,
        isImeComposing = ime,
    )

    private fun action(chord: ComposerEnterChord, enterSends: Boolean = false) =
        composerKeyAction(chord, enterSendsMessage = enterSends, handlesHardwareEnter = true)

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
    fun `an enter that is confirming an IME candidate is left alone`() {
        for (enterSends in listOf(false, true)) {
            assertEquals(ComposerKeyAction.Ignore, action(chord(ime = true), enterSends))
            assertEquals(ComposerKeyAction.Ignore, action(chord(shift = true, ime = true), enterSends))
            assertEquals(
                ComposerKeyAction.Ignore,
                action(chord(sendModifier = true, ime = true), enterSends),
            )
        }
    }

    @Test
    fun `key up and other keys are ignored`() {
        assertEquals(ComposerKeyAction.Ignore, action(chord(isKeyDown = false)))
        assertEquals(ComposerKeyAction.Ignore, action(chord(isEnter = false)))
    }

    @Test
    fun `mobile leaves enter to the IME`() {
        for (enterSends in listOf(false, true)) {
            assertEquals(
                ComposerKeyAction.Ignore,
                composerKeyAction(chord(), enterSends, handlesHardwareEnter = false),
            )
            assertEquals(
                ComposerKeyAction.Ignore,
                composerKeyAction(chord(shift = true), enterSends, handlesHardwareEnter = false),
            )
        }
    }
}
