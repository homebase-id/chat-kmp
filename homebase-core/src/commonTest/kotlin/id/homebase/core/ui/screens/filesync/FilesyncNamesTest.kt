package id.homebase.core.ui.screens.filesync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilesyncNamesTest {

    private fun taken(vararg names: String) = names.map(FilesyncNames::key).toSet()

    @Test
    fun aFreeNameIsKept() {
        assertEquals("Report.pdf", FilesyncNames.freeName("Report.pdf", taken("Other.pdf")))
    }

    @Test
    fun aTakenNameIsNumberedBeforeTheExtension() {
        assertEquals("Report (1).pdf", FilesyncNames.freeName("Report.pdf", taken("Report.pdf")))
        assertEquals("Report (2).pdf", FilesyncNames.freeName("Report.pdf", taken("Report.pdf", "report (1).PDF")))
    }

    @Test
    fun collisionsIgnoreCaseAndUnicodeForm() {
        assertEquals("CAFÉ (1).txt", FilesyncNames.freeName("CAFÉ.txt", taken("café.txt")))
    }

    @Test
    fun namesAreStoredInNfc() {
        assertEquals("café.txt", FilesyncNames.freeName("café.txt", emptySet()))
    }

    @Test
    fun forbiddenCharactersAndReservedNamesAreReplaced() {
        assertEquals("a_b", FilesyncNames.freeName("a/b", emptySet()))
        assertEquals("a_b", FilesyncNames.freeName("a\u0000b", emptySet()))
        for (reserved in listOf("", ".", "..")) {
            assertEquals("untitled", FilesyncNames.freeName(reserved, emptySet()))
        }
    }

    @Test
    fun aHiddenFileHasNoExtension() {
        assertEquals(".env (1)", FilesyncNames.freeName(".env", taken(".env")))
    }

    @Test
    fun aLongNameThatIsTakenIsNumberedWithinTheLimit() {
        val long = "a".repeat(251) + ".txt"
        val taken = taken(long)
        val first = FilesyncNames.freeName(long, taken)
        val second = FilesyncNames.freeName(long, taken + FilesyncNames.key(first))
        assertTrue(first.endsWith(" (1).txt") && first.encodeToByteArray().size <= 255, first)
        assertTrue(second.endsWith(" (2).txt") && second.encodeToByteArray().size <= 255, second)
    }

    @Test
    fun longNamesAreCutToFitWithoutSplittingACharacterAndKeepTheirExtension() {
        val name = FilesyncNames.freeName("📁".repeat(100) + ".jpeg", emptySet())
        assertTrue(name.encodeToByteArray().size <= 255)
        assertTrue(name.endsWith(".jpeg"))
        assertEquals(name, name.encodeToByteArray().decodeToString())
    }
}
