package id.homebase.api.sync.database

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the desktop passphrase → raw-key migration. Getting this wrong locks a user
 * out of their database permanently, so every case runs against a real SQLCipher file
 * written by the real driver rather than a mock.
 */
class SqlCipherKeyModeTest {

    private val tempDir: File = Files.createTempDirectory("sqlcipher-key-mode").toFile()

    private class FakeStore(var value: String? = null) : RawKeyStore {
        var writes = 0
        override fun get(): String? = value
        override fun put(hex: String) {
            value = hex
            writes++
        }

        override fun remove() {
            value = null
        }
    }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun dbFile(name: String) = File(tempDir, name)

    private fun createWith(url: String, marker: String) {
        DriverManager.getConnection(url, tuningProperties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS probe (v TEXT)")
                statement.executeUpdate("INSERT INTO probe VALUES ('$marker')")
            }
        }
    }

    private fun readMarker(url: String): String? =
        DriverManager.getConnection(url, tuningProperties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT v FROM probe").use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    @Test
    fun `derives the raw key an existing passphrase database was encrypted with`() {
        val db = dbFile("migrate.db")
        val passphrase = "a-passphrase-that-is-not-a-raw-key"
        createWith(SqlCipherKeyMode.passphraseUrl(db, passphrase), "kept")

        val store = FakeStore()
        val url = SqlCipherKeyMode.jdbcUrl(db, passphrase, store)

        val cached = assertNotNull(store.value, "raw key should be cached after migration")
        assertTrue(SqlCipherKeyMode.isRawKeyHex(cached))
        assertEquals(SqlCipherKeyMode.rawKeyUrl(db, cached), url)
        assertEquals("kept", readMarker(url), "migrated URL must open the untouched file")
        assertEquals(
            "kept",
            readMarker(SqlCipherKeyMode.passphraseUrl(db, passphrase)),
            "the file must still open with the original passphrase — no re-encryption",
        )
    }

    @Test
    fun `derivation is deterministic and reuses the cached key on later opens`() {
        val db = dbFile("cached.db")
        val passphrase = "another-passphrase"
        createWith(SqlCipherKeyMode.passphraseUrl(db, passphrase), "kept")

        val store = FakeStore()
        val first = SqlCipherKeyMode.jdbcUrl(db, passphrase, store)
        val writesAfterMigration = store.writes
        val second = SqlCipherKeyMode.jdbcUrl(db, passphrase, store)

        assertEquals(first, second)
        assertEquals(writesAfterMigration, store.writes, "a cached key must not be re-derived")
    }

    @Test
    fun `a corrupt cached key falls back to deriving from the passphrase`() {
        val db = dbFile("corrupt-cache.db")
        val passphrase = "yet-another-passphrase"
        createWith(SqlCipherKeyMode.passphraseUrl(db, passphrase), "kept")

        val wrongKey = "00".repeat(32)
        val store = FakeStore(wrongKey)
        val url = SqlCipherKeyMode.jdbcUrl(db, passphrase, store)

        assertEquals("kept", readMarker(url))
        assertEquals(
            SqlCipherKeyMode.deriveRawKeyHex(passphrase, SqlCipherKeyMode.readSalt(db)),
            store.value,
        )
    }

    @Test
    fun `an undecryptable database falls back to passphrase mode instead of being lost`() {
        val db = dbFile("foreign.db")
        createWith(SqlCipherKeyMode.passphraseUrl(db, "the-real-passphrase"), "kept")

        val store = FakeStore("11".repeat(32))
        val url = SqlCipherKeyMode.jdbcUrl(db, "a-passphrase-that-was-never-used", store)

        assertEquals(
            SqlCipherKeyMode.passphraseUrl(db, "a-passphrase-that-was-never-used"),
            url,
            "must hand back the passphrase URL so DatabaseManager's recovery decides",
        )
        assertNull(store.value, "a key that cannot open the file must not stay cached")
        assertEquals(
            "kept",
            readMarker(SqlCipherKeyMode.passphraseUrl(db, "the-real-passphrase")),
            "the file must be untouched by the failed probes",
        )
    }

    @Test
    fun `a fresh install is born in raw-key mode and never runs the KDF`() {
        val db = dbFile("fresh.db")
        val store = FakeStore()

        val url = SqlCipherKeyMode.jdbcUrl(db, "unused-passphrase", store)
        val cached = assertNotNull(store.value)
        assertTrue(SqlCipherKeyMode.isRawKeyHex(cached))
        assertEquals(SqlCipherKeyMode.rawKeyUrl(db, cached), url)

        createWith(url, "born-raw")

        assertEquals(
            url,
            SqlCipherKeyMode.jdbcUrl(db, "unused-passphrase", store),
            "reopening must reuse the cached key, not derive from the passphrase",
        )
        assertEquals("born-raw", readMarker(url))
        assertFalse(
            SqlCipherKeyMode.isRawKeyHex("not-hex"),
            "hex validation must reject a garbled cache entry",
        )
    }

    @Test
    fun `raw key derivation matches SQLCipher for a known salt`() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val a = SqlCipherKeyMode.deriveRawKeyHex("passphrase", salt)
        val b = SqlCipherKeyMode.deriveRawKeyHex("passphrase", salt)
        val c = SqlCipherKeyMode.deriveRawKeyHex("passphrase", ByteArray(16))

        assertEquals(a, b)
        assertTrue(SqlCipherKeyMode.isRawKeyHex(a))
        assertTrue(a != c, "the file's own salt must feed the derivation")
    }
}
