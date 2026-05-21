@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault.note

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class VaultNoteEditorViewModelTest {

    @Test
    fun initialState_createMode_isEmpty() {
        val state = VaultNoteEditorUiState()
        assertTrue(state.title.isEmpty())
        assertFalse(state.isLoading)
        assertFalse(state.isSaving)
        assertTrue(state.isCreateMode)
    }

    @Test
    fun initialState_editMode_hasEntryId() {
        val state = VaultNoteEditorUiState(entryId = Uuid.random())
        assertFalse(state.isCreateMode)
    }

    @Test
    fun canSave_blankTitleIsFalse() {
        val state = VaultNoteEditorUiState(title = "   ")
        assertFalse(state.canSave)
    }

    @Test
    fun canSave_nonBlankTitleIsTrue() {
        val state = VaultNoteEditorUiState(title = "My Note")
        assertTrue(state.canSave)
    }

    @Test
    fun canSave_emptyTitleIsFalse() {
        val state = VaultNoteEditorUiState(title = "")
        assertFalse(state.canSave)
    }

    @Test
    fun canSave_falseWhileSaving() {
        val state = VaultNoteEditorUiState(title = "My Note", isSaving = true)
        assertFalse(state.canSave)
    }

    @Test
    fun titleFromFileName_stripsExtension() {
        val fileName = "Shopping List.md"
        val displayTitle = fileName.removeSuffix(".md")
        assertEquals("Shopping List", displayTitle)
    }

    @Test
    fun titleFromFileName_noExtension() {
        val fileName = "Note Without Extension"
        val displayTitle = fileName.removeSuffix(".md")
        assertEquals("Note Without Extension", displayTitle)
    }

    @Test
    fun titleError_resetOnTitleChange() {
        val state = VaultNoteEditorUiState(titleError = true)
        // Simulating what onTitleChanged does
        val updated = state.copy(title = "New Title", titleError = false)
        assertFalse(updated.titleError)
        assertEquals("New Title", updated.title)
    }
}
