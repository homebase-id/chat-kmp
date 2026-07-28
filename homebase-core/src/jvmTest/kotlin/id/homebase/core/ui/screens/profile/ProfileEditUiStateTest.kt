package id.homebase.core.ui.screens.profile

import id.homebase.api.client.profile.ProfileVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileEditUiStateTest {

    @Test
    fun value_anonymousTier_returnsAnonymousMapEntry() {
        val state = ProfileEditUiState(anonymousValues = mapOf(ProfileField.NICKNAME to "abc"))
        assertEquals("abc", state.value(ProfileField.NICKNAME, ProfileVisibility.ANONYMOUS))
    }

    @Test
    fun value_connectedTier_returnsConnectedMapEntry() {
        val state = ProfileEditUiState(connectedValues = mapOf(ProfileField.NICKNAME to "ppp"))
        assertEquals("ppp", state.value(ProfileField.NICKNAME, ProfileVisibility.CONNECTED))
    }

    @Test
    fun value_fieldPresentOnlyInAnonymous_connectedTierReturnsEmpty() {
        // No automatic cross-tier fallback in the raw accessor — that's a display-time concern
        // owned by ProfilePreview, not storage.
        val state = ProfileEditUiState(anonymousValues = mapOf(ProfileField.NICKNAME to "abc"))
        assertEquals("", state.value(ProfileField.NICKNAME, ProfileVisibility.CONNECTED))
    }

    @Test
    fun isSaving_pairInSavingAttributes_true() {
        val state = ProfileEditUiState(
            savingAttributes = setOf("nickname" to ProfileVisibility.CONNECTED),
        )
        assertTrue(state.isSaving("nickname", ProfileVisibility.CONNECTED))
    }

    @Test
    fun isSaving_pairNotInSavingAttributes_false() {
        val state = ProfileEditUiState(
            savingAttributes = setOf("nickname" to ProfileVisibility.CONNECTED),
        )
        assertTrue(!state.isSaving("nickname", ProfileVisibility.ANONYMOUS))
        assertTrue(!state.isSaving("email", ProfileVisibility.CONNECTED))
    }
}
