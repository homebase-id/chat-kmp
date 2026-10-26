package id.homebase.api.sync.database

import id.homebase.api.storage.SecureRead
import id.homebase.api.storage.toSecureRead
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.getVersion
import co.touchlab.sqliter.longForQuery
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class IosDatabaseEncryptionTest {

    private val name = "encryption-test.db"
    private val path = databasePath(name)
    private val passphrase = "passphrase"

    private class FakeStore(var value: String? = null) : RawKeyStore {
        var locked = false
        var writes = 0
        override fun put(hex: String) { value = hex; writes++ }
        override fun read(): SecureRead =
            if (locked) SecureRead.Unavailable(IllegalStateException("locked")) else value.toSecureRead()
    }

    @BeforeTest
    @AfterTest
    fun cleanup() {
        deleteDatabaseFiles(path)
    }

    @Test
    fun keyedDatabaseIsEncryptedOnDisk() {
        val key = SqlCipherRawKey.format("cd".repeat(32))
        openNativeSqliteDriver(name, key).useDriver { it.writeMarker("secret") }

        assertFalse(isPlaintextDatabase(path), "odin DB file starts with the plaintext SQLite header")
        assertFails("the file opened without its key") { schemaRows(key = null) }
        openNativeSqliteDriver(name, key).useDriver { assertEquals("secret", it.readMarker()) }
    }

    @Test
    fun freshDatabaseIsEncryptedWithTheStoredRawKey() {
        val store = FakeStore()
        openKeyedNativeSqliteDriver(name, passphrase, store).useDriver { it.writeMarker("fresh") }

        val hex = assertNotNull(store.value)
        assertTrue(SqlCipherRawKey.isHex(hex))
        assertFalse(isPlaintextDatabase(path))
        assertFails { schemaRows(key = null) }
        openNativeSqliteDriver(name, SqlCipherRawKey.format(hex)).useDriver { assertEquals("fresh", it.readMarker()) }
    }

    @Test
    fun plaintextDatabaseIsEncryptedInPlace() {
        openNativeSqliteDriver(name, key = null).useDriver { driver ->
            driver.writeMarker("kept")
            repeat(500) { driver.execute(null, "INSERT INTO marker VALUES ('row $it')", 0) }
        }
        assertTrue(isPlaintextDatabase(path))
        val version = userVersion(key = null)
        val tables = schemaRows(key = null)

        val store = FakeStore()
        openKeyedNativeSqliteDriver(name, passphrase, store).useDriver { driver ->
            assertEquals("kept", driver.readMarker())
            assertEquals(501L, driver.markerCount())
        }

        val key = SqlCipherRawKey.format(assertNotNull(store.value))
        assertFalse(isPlaintextDatabase(path))
        assertFalse(exists(exportPath(name)))
        assertFails { schemaRows(key = null) }
        assertEquals(version, userVersion(key))
        assertEquals(tables, schemaRows(key))
    }

    @Test
    fun encryptedDatabaseIsNotEncryptedAgain() {
        val store = FakeStore()
        openKeyedNativeSqliteDriver(name, passphrase, store).useDriver { it.writeMarker("once") }
        openKeyedNativeSqliteDriver(name, passphrase, store, encrypt = { _, _ -> fail("encrypted twice") })
            .useDriver { assertEquals("once", it.readMarker()) }
    }

    @Test
    fun failedEncryptionLeavesThePlaintextDatabaseUsable() {
        openNativeSqliteDriver(name, key = null).useDriver { it.writeMarker("kept") }
        val store = FakeStore()

        openKeyedNativeSqliteDriver(name, passphrase, store, encrypt = { n, k ->
            exportEncryptedCopy(n, k)
            error("interrupted before the swap")
        }).useDriver { assertEquals("kept", it.readMarker()) }

        assertTrue(isPlaintextDatabase(path))
        assertFalse(exists(exportPath(name)))
    }

    @Test
    fun copyLeftByAKilledEncryptionIsDiscarded() {
        openNativeSqliteDriver(name, key = null).useDriver { it.writeMarker("kept") }
        val store = FakeStore(SqlCipherRawKey.newHex())
        exportEncryptedCopy(name, SqlCipherRawKey.format(SqlCipherRawKey.newHex()))
        assertTrue(exists(exportPath(name)))

        openKeyedNativeSqliteDriver(name, passphrase, store).useDriver { assertEquals("kept", it.readMarker()) }

        assertFalse(isPlaintextDatabase(path))
        assertFalse(exists(exportPath(name)))
        assertEquals(1L, schemaRows(SqlCipherRawKey.format(assertNotNull(store.value)), "SELECT count(*) FROM marker"))
    }

    @Test
    fun lockedKeychainNeitherReplacesTheKeyNorTheEncryptedDatabase() {
        val store = FakeStore()
        openKeyedNativeSqliteDriver(name, passphrase, store).useDriver { it.writeMarker("kept") }
        val hex = assertNotNull(store.value)

        store.locked = true
        assertFailsWith<DatabaseUnavailableException> { openKeyedNativeSqliteDriver(name, passphrase, store) }

        assertEquals(hex, store.value)
        assertEquals(1, store.writes)
        store.locked = false
        openKeyedNativeSqliteDriver(name, passphrase, store).useDriver { assertEquals("kept", it.readMarker()) }
    }

    @Test
    fun missingKeyForAnEncryptedDatabaseWritesNoNewKey() {
        openNativeSqliteDriver(name, SqlCipherRawKey.format(SqlCipherRawKey.newHex())).useDriver { it.writeMarker("lost") }
        val store = FakeStore()

        assertFailsWith<IllegalStateException> { openKeyedNativeSqliteDriver(name, passphrase, store) }
        assertEquals(0, store.writes)
    }

    private fun exists(path: String) = FileSystem.SYSTEM.exists(path.toPath())

    private fun schemaRows(key: String?, sql: String = "SELECT count(*) FROM sqlite_master"): Long =
        withConnection(name, key, JournalMode.WAL) { it.longForQuery(sql) }

    private fun userVersion(key: String?): Int = withConnection(name, key, JournalMode.WAL) { it.getVersion() }

    private fun SqlDriver.useDriver(block: (SqlDriver) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun SqlDriver.writeMarker(value: String) {
        execute(null, "CREATE TABLE IF NOT EXISTS marker(v TEXT)", 0)
        execute(null, "INSERT INTO marker VALUES ('$value')", 0)
    }

    private fun SqlDriver.readMarker(): String? =
        executeQuery(null, "SELECT v FROM marker ORDER BY rowid LIMIT 1", { c ->
            c.next()
            QueryResult.Value(c.getString(0))
        }, 0).value

    private fun SqlDriver.markerCount(): Long? =
        executeQuery(null, "SELECT count(*) FROM marker", { c ->
            c.next()
            QueryResult.Value(c.getLong(0))
        }, 0).value
}
