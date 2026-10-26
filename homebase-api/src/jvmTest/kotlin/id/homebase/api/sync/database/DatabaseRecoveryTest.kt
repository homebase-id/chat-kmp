package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import id.homebase.api.storage.KeyStoreFileStorage
import id.homebase.api.storage.SecureRead
import id.homebase.api.storage.dataFile
import id.homebase.api.storage.makeStorageKeyUnrecoverable
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DatabaseRecoveryTest {

    private val tempDir: File = Files.createTempDirectory("db-recovery").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.walk().forEach { it.setReadable(true) }
        tempDir.deleteRecursively()
    }

    /** A real key store and SQLCipher file, launched the way Android/Desktop launch. */
    private inner class Install {
        val secureDir = File(tempDir, "secure").apply { mkdirs() }
        val storage = KeyStoreFileStorage(secureDir)
        val db = File(tempDir, "odin.db")
        var wipes = 0

        private val rawKeys = object : RawKeyStore {
            override fun read(): SecureRead = storage.read("odin_db_raw_key")
            override fun put(hex: String) = storage.put("odin_db_raw_key", hex)
        }

        suspend fun launch(onOpen: (SqlDriver) -> Unit) = DatabaseManager.initializeWithRecovery(
            createDriver = { openKeyedSqliteDriver(db, it, rawKeys) { derive -> derive() } },
            deleteOnDiskFiles = { wipes++; deleteDatabaseFiles(db.absolutePath) },
            getKey = { DatabaseKeyManager.getOrGenerateKey(storage.read(PASSPHRASE)) { storage.put(PASSPHRASE, it) } },
            clearKey = { storage.remove(PASSPHRASE) },
            open = { provider -> provider().use(onOpen) },
        )
    }

    private fun SqlDriver.writeMarker() {
        execute(null, "CREATE TABLE probe (v TEXT)", 0)
        execute(null, "INSERT INTO probe VALUES ('kept')", 0)
    }

    private fun SqlDriver.marker(): String? = executeQuery(
        null,
        "SELECT v FROM probe",
        { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
        0,
    ).value

    private fun SqlDriver.hasProbe(): Boolean = executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE name = 'probe'",
        { c -> QueryResult.Value(c.next().value && c.getLong(0) == 1L) },
        0,
    ).value

    @Test
    fun `an unavailable database is not wiped`() = runTest {
        var opens = 0
        var wipes = 0
        var keyClears = 0

        assertFailsWith<DatabaseUnavailableException> {
            DatabaseManager.initializeWithRecovery(
                createDriver = { opens++; throw DatabaseUnavailableException("key store not ready") },
                deleteOnDiskFiles = { wipes++ },
                getKey = { "passphrase" },
                clearKey = { keyClears++ },
            )
        }

        assertEquals(1, opens)
        assertEquals(0, wipes)
        assertEquals(0, keyClears)
    }

    @Test
    fun `a storage key lost for good wipes and opens a fresh database instead of throwing`() = runTest {
        val install = Install()
        install.launch { it.writeMarker() }
        makeStorageKeyUnrecoverable(install.secureDir)

        var reopened: Boolean? = null
        install.launch { reopened = it.hasProbe() }

        assertEquals(1, install.wipes)
        assertEquals(false, reopened, "the fresh database must not hold the old rows")
        assertIs<SecureRead.Found>(install.storage.read(PASSPHRASE))
        assertIs<SecureRead.Found>(install.storage.read("odin_db_raw_key"))
    }

    @Test
    fun `a key store that can't be read right now throws and keeps the database`() = runTest {
        val install = Install()
        install.launch { it.writeMarker() }
        dataFile(install.secureDir).setReadable(false)

        assertFailsWith<DatabaseUnavailableException> { install.launch { error("must not open") } }
        assertEquals(0, install.wipes)
        assertTrue(install.db.isFile)

        dataFile(install.secureDir).setReadable(true)
        var marker: String? = null
        install.launch { marker = it.marker() }
        assertEquals("kept", marker)
        assertEquals(0, install.wipes)
    }

    private companion object {
        const val PASSPHRASE = "odin_db_encryption_key"
    }
}
