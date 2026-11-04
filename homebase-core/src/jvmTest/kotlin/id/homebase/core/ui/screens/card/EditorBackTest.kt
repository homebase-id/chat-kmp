package id.homebase.core.ui.screens.card

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorBackTest {
    @Test
    fun backFromCustomiseEnteredFromTheCardGoesToTheCardNotThePicker() {
        assertEquals(EditorBack.CloseDesign, editorBack(EditorMode.Design, EditorStep.Customise, true, true, false))
        assertEquals(EditorBack.ConfirmDiscard, editorBack(EditorMode.Design, EditorStep.Customise, true, true, true))
    }

    @Test
    fun backFromCustomiseReachedViaThePickerReturnsToThePicker() {
        assertEquals(EditorBack.ToDesignStep, editorBack(EditorMode.Design, EditorStep.Customise, false, true, true))
    }

    @Test
    fun backFromThePickerClosesOrConfirms() {
        assertEquals(EditorBack.CloseDesign, editorBack(EditorMode.Design, EditorStep.Design, true, true, false))
        assertEquals(EditorBack.ConfirmDiscard, editorBack(EditorMode.Design, EditorStep.Design, false, true, true))
    }
}
