@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pins [ProfileEditViewModel.computeAttributeEdit] — the change-detection logic that decides
 * whether an attribute needs saving, and its consumer [ProfileEditViewModel.pendingEdits], which
 * carries the "an untouched legacy attribute's visibility never silently changes" guarantee: a
 * loaded Owner/Authenticated attribute in the Connected bucket keeps its real stored visibility
 * unless the user actually edits that tab's content.
 */
class ProfileEditViewModelComputeEditTest {

    private fun attribute(
        data: JsonObject,
        visibility: ProfileVisibility = ProfileVisibility.OWNER,
    ): ProfileAttribute = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.EMAIL,
        versionTag = Uuid.random(),
        visibility = visibility,
        data = data,
    )

    @Test
    fun untouchedAttribute_sameData_returnsNull() {
        val data = JsonObject(mapOf(ProfileAttributeTypes.KEY_EMAIL to JsonPrimitive("a@b.com")))
        val existing = attribute(data)

        val result = ProfileEditViewModel.computeAttributeEdit(
            existing = existing,
            type = ProfileAttributeTypes.EMAIL,
            updates = mapOf(ProfileAttributeTypes.KEY_EMAIL to "a@b.com"),
            tier = ProfileVisibility.CONNECTED,
        )

        assertNull(result)
    }

    @Test
    fun textChange_connectedTier_becomesConnectedVisibility() {
        // Editing the Connected tab's content is an explicit choice to make it connected-visible,
        // even if the underlying legacy record was OWNER — Preview must never promise visibility
        // the server isn't actually enforcing.
        val data = JsonObject(mapOf(ProfileAttributeTypes.KEY_EMAIL to JsonPrimitive("a@b.com")))
        val existing = attribute(data, visibility = ProfileVisibility.OWNER)

        val result = ProfileEditViewModel.computeAttributeEdit(
            existing = existing,
            type = ProfileAttributeTypes.EMAIL,
            updates = mapOf(ProfileAttributeTypes.KEY_EMAIL to "new@b.com"),
            tier = ProfileVisibility.CONNECTED,
        )

        assertEquals(ProfileVisibility.CONNECTED, result?.visibility)
    }

    @Test
    fun textChange_anonymousTier_alwaysAnonymousVisibility() {
        val data = JsonObject(mapOf(ProfileAttributeTypes.KEY_EMAIL to JsonPrimitive("a@b.com")))
        val existing = attribute(data, visibility = ProfileVisibility.ANONYMOUS)

        val result = ProfileEditViewModel.computeAttributeEdit(
            existing = existing,
            type = ProfileAttributeTypes.EMAIL,
            updates = mapOf(ProfileAttributeTypes.KEY_EMAIL to "new@b.com"),
            tier = ProfileVisibility.ANONYMOUS,
        )

        assertEquals(ProfileVisibility.ANONYMOUS, result?.visibility)
    }

    @Test
    fun brandNewAttribute_allBlank_returnsNull() {
        val result = ProfileEditViewModel.computeAttributeEdit(
            existing = null,
            type = ProfileAttributeTypes.EMAIL,
            updates = mapOf(ProfileAttributeTypes.KEY_EMAIL to "", ProfileAttributeTypes.KEY_LABEL to " "),
            tier = ProfileVisibility.CONNECTED,
        )

        assertNull(result)
    }

    @Test
    fun brandNewAttribute_withValue_usesSuppliedTierVisibility() {
        val result = ProfileEditViewModel.computeAttributeEdit(
            existing = null,
            type = ProfileAttributeTypes.EMAIL,
            updates = mapOf(ProfileAttributeTypes.KEY_EMAIL to "a@b.com"),
            tier = ProfileVisibility.CONNECTED,
        )

        assertEquals(ProfileVisibility.CONNECTED, result?.visibility)
    }

    // --- pendingEdits(): the "untouched legacy OWNER stays OWNER" regression test ---

    @Test
    fun pendingEdits_untouchedLegacyOwnerAttribute_producesNoEdit() {
        val ownerNickname = attribute(
            data = JsonObject(mapOf(ProfileAttributeTypes.KEY_NICKNAME to JsonPrimitive("old-nickname"))),
            visibility = ProfileVisibility.OWNER,
        ).copy(type = ProfileAttributeTypes.NICKNAME)

        // The Connected tab was never opened/edited — its value is exactly what was loaded.
        val uiState = ProfileEditUiState(
            connectedValues = mapOf(ProfileField.NICKNAME to "old-nickname"),
        )

        val edits = ProfileEditViewModel.pendingEdits(
            s = uiState,
            loadedAnonymous = emptyMap(),
            loadedConnected = mapOf(ProfileAttributeTypes.NICKNAME to ownerNickname),
        )

        assertTrue(edits.none { it.type == ProfileAttributeTypes.NICKNAME })
    }

    @Test
    fun pendingEdits_editedLegacyOwnerAttribute_promotesToConnected() {
        val ownerNickname = attribute(
            data = JsonObject(mapOf(ProfileAttributeTypes.KEY_NICKNAME to JsonPrimitive("old-nickname"))),
            visibility = ProfileVisibility.OWNER,
        ).copy(type = ProfileAttributeTypes.NICKNAME)

        // The user typed a new value into the Connected tab.
        val uiState = ProfileEditUiState(
            connectedValues = mapOf(ProfileField.NICKNAME to "ppp"),
        )

        val edits = ProfileEditViewModel.pendingEdits(
            s = uiState,
            loadedAnonymous = emptyMap(),
            loadedConnected = mapOf(ProfileAttributeTypes.NICKNAME to ownerNickname),
        )

        val nicknameEdit = edits.single { it.type == ProfileAttributeTypes.NICKNAME }
        assertEquals(ProfileVisibility.CONNECTED, nicknameEdit.visibility)
    }
}
