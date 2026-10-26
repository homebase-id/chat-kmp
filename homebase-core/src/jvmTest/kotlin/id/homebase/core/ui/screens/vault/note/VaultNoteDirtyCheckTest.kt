package id.homebase.core.ui.screens.vault.note

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultNoteDirtyCheckTest {

    private fun dirty(current: String, loaded: String?, title: String = "T", loadedTitle: String? = "T") =
        isVaultNoteDirty(
            isCreateMode = false,
            title = title,
            currentMarkdown = current,
            loadedTitle = loadedTitle,
            loadedMarkdown = loaded,
        )

    @Test
    fun unchangedLoadedNoteIsNotDirty() {
        assertFalse(dirty(current = "hello", loaded = "hello"))
    }

    @Test
    fun trailingBrArtifactIsNotDirty() {
        assertFalse(dirty(current = "hello", loaded = "hello\n<br>"))
    }

    @Test
    fun editedBodyIsDirty() {
        assertTrue(dirty(current = "hello world", loaded = "hello"))
    }

    @Test
    fun editedTitleIsDirty() {
        assertTrue(dirty(current = "hello", loaded = "hello", title = "New"))
    }

    @Test
    fun createModeWithContentIsDirty() {
        assertTrue(isVaultNoteDirty(true, "", "x", null, null))
        assertFalse(isVaultNoteDirty(true, "", "", null, null))
    }
}
