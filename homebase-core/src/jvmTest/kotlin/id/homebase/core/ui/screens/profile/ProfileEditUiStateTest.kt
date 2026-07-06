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
    fun emailValid_malformedInAnonymous_false() {
        val state = ProfileEditUiState(anonymousValues = mapOf(ProfileField.EMAIL to "not-an-email"))
        assertTrue(!state.emailValid)
    }

    @Test
    fun emailValid_malformedInConnected_false() {
        val state = ProfileEditUiState(connectedValues = mapOf(ProfileField.EMAIL to "not-an-email"))
        assertTrue(!state.emailValid)
    }

    @Test
    fun emailValid_bothBlank_true() {
        assertTrue(ProfileEditUiState().emailValid)
    }

    @Test
    fun emailValid_bothWellFormed_true() {
        val state = ProfileEditUiState(
            anonymousValues = mapOf(ProfileField.EMAIL to "public@example.com"),
            connectedValues = mapOf(ProfileField.EMAIL to "private@example.com"),
        )
        assertTrue(state.emailValid)
    }

    @Test
    fun phoneValid_malformedInAnonymous_false() {
        val state = ProfileEditUiState(anonymousValues = mapOf(ProfileField.PHONE to "555-1234"))
        assertTrue(!state.phoneValid)
    }

    @Test
    fun phoneValid_malformedInConnected_false() {
        val state = ProfileEditUiState(connectedValues = mapOf(ProfileField.PHONE to "555-1234"))
        assertTrue(!state.phoneValid)
    }

    @Test
    fun phoneValid_bothBlank_true() {
        assertTrue(ProfileEditUiState().phoneValid)
    }

    @Test
    fun canSave_falseWhileLoading() {
        assertTrue(!ProfileEditUiState(isLoading = true).canSave)
    }

    @Test
    fun canSave_trueWhenLoadedAndValid() {
        val state = ProfileEditUiState(isLoading = false)
        assertTrue(state.canSave)
    }

    @Test
    fun canSave_falseWithMalformedEmail() {
        val state = ProfileEditUiState(
            isLoading = false,
            anonymousValues = mapOf(ProfileField.EMAIL to "not-an-email"),
        )
        assertTrue(!state.canSave)
    }
}
