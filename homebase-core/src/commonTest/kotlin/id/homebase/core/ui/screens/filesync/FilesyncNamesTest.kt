package id.homebase.core.ui.screens.filesync

import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.filesync.FilesyncRoleFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

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

    @Test
    fun aRestoredItemKeepsItsNameWhileNoLiveSiblingHasIt() {
        assertEquals("Report.pdf", FilesyncNames.restoredName("Report.pdf", taken("other.pdf")))
    }

    @Test
    fun aRestoredItemWhoseNameWasTakenIsTaggedBeforeItsExtension() {
        val once = taken("report.PDF")
        assertEquals("Report (restored).pdf", FilesyncNames.restoredName("Report.pdf", once))
        assertEquals(
            "Report (restored 2).pdf",
            FilesyncNames.restoredName("Report.pdf", once + FilesyncNames.key("Report (restored).pdf")),
        )
        assertEquals(".env (restored)", FilesyncNames.restoredName(".env", taken(".env")))
        assertEquals("Makefile (restored)", FilesyncNames.restoredName("Makefile", taken("Makefile")))
    }

    @Test
    fun aRestoredNameStaysWithinTheLimitAndGivesUpAfter99() {
        val long = "a".repeat(251) + ".txt"
        val restored = FilesyncNames.restoredName(long, taken(long))!!
        assertTrue(restored.endsWith(" (restored).txt") && restored.encodeToByteArray().size <= 255, restored)

        val all = taken("x.txt", "x (restored).txt") + (2..99).map { FilesyncNames.key("x (restored $it).txt") }
        assertEquals(null, FilesyncNames.restoredName("x.txt", all))
    }

    @Test
    fun recoveredFilesLeavesItsNameFreeForAPlainFolder() {
        fun folder(id: Uuid, name: String) = FilesyncNode(id, FilesyncProtocol.RootNodeId, true, name, null, null, updatedMs = 1)
        val nodes = listOf(folder(FilesyncProtocol.RecoveredFilesNodeId, "Recovered files"), folder(Uuid.random(), "Photos"))
        val taken = FilesyncNames.takenKeys(FilesyncProtocol.RootNodeId, nodes)
        assertEquals("Recovered files", FilesyncNames.freeName("Recovered files", taken))
        assertEquals(setOf(FilesyncNames.key("Photos"), FilesyncNames.key(".hbsync")), taken)
    }

    @Test
    fun collisionsFoldCaseAsTheDesktopDoes() {
        assertEquals("STRASSE (1).txt", FilesyncNames.freeName("STRASSE.txt", taken("Straße.txt")))
        assertEquals("ſign (1)", FilesyncNames.freeName("ſign", taken("Sign")))
        assertEquals("STRAẞE (1).txt", FilesyncNames.freeName("STRAẞE.txt", taken("Straße.txt")))
        assertEquals("ıd.txt", FilesyncNames.freeName("ıd.txt", taken("ID.txt")))
        // Final or not, a sigma is one letter: a search typed with ς finds a name whose key would otherwise hold σ.
        assertEquals(FilesyncNames.key("ΛΟΓΑΡΙΑΣΜΌΣ.pdf"), FilesyncNames.key("λογαριασμός.pdf"))
        assertTrue(FilesyncNames.key("λογαριασμός") in FilesyncNames.key("Λογαριασμός.pdf"))
    }

    @Test
    fun theStateFolderNameIsTakenAtTheTopInAnySpelling() {
        val top = FilesyncNames.takenKeys(FilesyncProtocol.RootNodeId, emptyList())
        for (name in listOf(".hbsync", ".HBSYNC", ".HbSync", ".hb\u017fync")) {
            assertEquals(FilesyncNames.stateFolderKey, FilesyncNames.key(name), name)
            assertEquals("$name (1)", FilesyncNames.freeName(name, top))
            assertEquals("$name (restored)", FilesyncNames.restoredName(name, top))
        }
        assertEquals(".hbsync", FilesyncNames.freeName(".hbsync", FilesyncNames.takenKeys(Uuid.random(), emptyList())))
        assertEquals(".hbsync (1)", FilesyncNames.freeName(".hbsync", FilesyncNames.takenKeys(FilesyncRoleFolder.Documents.nodeId, emptyList())))
        assertEquals(".hbsync2", FilesyncNames.freeName(".hbsync2", top))
    }

    @Test
    fun onlyTheStateFolderNameAtTheTopIsNotOnComputers() {
        fun node(parent: Uuid, name: String) = FilesyncNode(Uuid.random(), parent, true, name, null, null, updatedMs = 1)
        assertTrue(node(FilesyncProtocol.RootNodeId, ".HBSYNC").notOnComputers)
        assertTrue(node(FilesyncRoleFolder.Pictures.nodeId, ".hbsync").notOnComputers)
        assertFalse(node(Uuid.random(), ".hbsync").notOnComputers)
        assertFalse(node(FilesyncProtocol.RootNodeId, ".hbsync2").notOnComputers)
    }
}
