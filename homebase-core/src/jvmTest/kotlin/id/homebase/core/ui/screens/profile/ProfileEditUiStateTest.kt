package id.homebase.core.ui.screens.profile

import id.homebase.core.ui.screens.card.CardCircle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileEditUiStateTest {

    @Test
    fun anUnsetAttributeStartsPublic() {
        assertEquals(ProfileAudience.Public, ProfileEditUiState().audience("phone"))
    }

    @Test
    fun valueIsBlankWhenNotSet() {
        assertEquals("", ProfileEditUiState().value(ProfileField.NICKNAME))
        assertEquals("abc", ProfileEditUiState(values = mapOf(ProfileField.NICKNAME to "abc")).value(ProfileField.NICKNAME))
    }

    @Test
    fun isSavingIsPerAttributeType() {
        val state = ProfileEditUiState(savingAttributes = setOf("nickname"))
        assertTrue(state.isSaving("nickname"))
        assertFalse(state.isSaving("email"))
    }

    @Test
    fun circlesAudienceWithNoCircleIsNotSavable() {
        assertFalse(ProfileAudience.Circles(emptySet()).isSavable)
        assertTrue(ProfileAudience.Circles(setOf(CIRCLE)).isSavable)
        assertTrue(ProfileAudience.OnlyMe.isSavable)
    }

    private companion object {
        const val CIRCLE = "cefc4f7cbc8c34762e0f76703e7e174e"
    }
}
