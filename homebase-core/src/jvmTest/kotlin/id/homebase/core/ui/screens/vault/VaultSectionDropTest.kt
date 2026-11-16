@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import io.github.vinceglb.filekit.PlatformFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class VaultSectionDropTest {
    private val sectionA = Uuid.random()
    private val files = listOf(PlatformFile(File("a.pdf")), PlatformFile(File("b.png")))

    @Test
    fun dropBecomesAddEntryToTheDroppedSectionOnly() {
        val action = vaultSectionDropAction(sectionA, files)
        assertIs<VaultUiAction.AddEntryToSection>(action)
        assertEquals(sectionA, action.sectionId)
        assertEquals(files, action.files)
        assertNull(action.entryName)
    }

    @Test
    fun emptyDropAddsNothing() {
        assertNull(vaultSectionDropAction(sectionA, emptyList()))
    }
}
