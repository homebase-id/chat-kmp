package id.homebase.core.ui.screens.contactbook

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactDraftValidityTest {

    private val base = ContactDraft(givenName = "Sam")

    @Test
    fun `no birthday is savable`() {
        assertTrue(base.isSavable)
        assertTrue(base.birthdayValid)
    }

    @Test
    fun `iso birthday is savable`() {
        val draft = base.copy(birthday = "1969-02-22")
        assertTrue(draft.birthdayValid)
        assertTrue(draft.isSavable)
    }

    @Test
    fun `malformed birthday blocks save`() {
        val draft = base.copy(birthday = "1969-02-222")
        assertFalse(draft.birthdayValid)
        assertFalse(draft.isSavable)
    }

    @Test
    fun `impossible birthday blocks save`() {
        assertFalse(base.copy(birthday = "2023-02-29").isSavable)
        assertFalse(base.copy(birthday = "1969-13-01").isSavable)
    }
}
