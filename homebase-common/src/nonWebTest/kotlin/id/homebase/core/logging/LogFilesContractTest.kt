package id.homebase.core.logging

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogFilesContractTest {

    private fun withDir(block: (Path) -> Unit) {
        val dir = Path(SystemTemporaryDirectory, "log-contract-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(dir)
        try {
            block(dir)
        } finally {
            SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.delete(dir, mustExist = false)
        }
    }

    private fun touch(dir: Path, name: String) {
        SystemFileSystem.sink(Path(dir, name)).buffered().use { it.writeString("x") }
    }

    @Test
    fun listsOnlyHomebaseLogFiles() = withDir { dir ->
        listOf("homebase.log", "homebase-1.log", "other.log", "homebase.txt", "notes.md").forEach { touch(dir, it) }
        assertEquals(
            setOf("homebase.log", "homebase-1.log"),
            listHomebaseLogFiles(dir).map { it.name }.toSet(),
        )
    }

    @Test
    fun emptyDirectoryListsNothing() = withDir { dir ->
        assertEquals(emptyList(), listHomebaseLogFiles(dir))
        assertTrue(deleteHomebaseLogFiles(dir))
    }

    @Test
    fun deleteRemovesLogFilesAndLeavesTheRest() = withDir { dir ->
        listOf("homebase.log", "homebase-2.log", "other.log").forEach { touch(dir, it) }
        assertTrue(deleteHomebaseLogFiles(dir))
        assertEquals(emptyList(), listHomebaseLogFiles(dir))
        assertTrue(SystemFileSystem.exists(Path(dir, "other.log")))
    }
}
