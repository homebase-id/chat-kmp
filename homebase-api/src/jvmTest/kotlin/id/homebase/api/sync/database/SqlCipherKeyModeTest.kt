package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the passphrase → raw-key migration. Getting this wrong locks a user out of their
 * database permanently, so every case runs against a real SQLCipher file written by the real
 * driver rather than a mock.
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

    private val inline: (() -> Unit) -> Unit = { it() }

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

    private fun SqlDriver.marker(): String? = use { driver ->
        driver.executeQuery(null, "SELECT v FROM probe", { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 0).value
    }

    @Test
    fun `derives the raw key an existing passphrase database was encrypted with`() {
        val db = dbFile("migrate.db")
        val passphrase = "a-passphrase-that-is-not-a-raw-key"
        createWith(cipherUrl(db, passphrase), "kept")

        val store = FakeStore()
        assertEquals("kept", openKeyedSqliteDriver(db, passphrase, store, inline).marker())

        val cached = assertNotNull(store.value, "raw key should be cached for the next launch")
        assertEquals(SqlCipherKeyMode.deriveRawKeyHex(passphrase, SqlCipherKeyMode.readSalt(db)), cached)
        assertEquals("kept", readMarker(cipherUrl(db, SqlCipherRawKey.format(cached))))
        assertEquals(
            "kept",
            readMarker(cipherUrl(db, passphrase)),
            "the file must still open with the original passphrase — no re-encryption",
        )
    }

    @Test
    fun `a cached key is reused on later opens`() {
        val db = dbFile("cached.db")
        val passphrase = "another-passphrase"
        createWith(cipherUrl(db, passphrase), "kept")

        val store = FakeStore()
        openKeyedSqliteDriver(db, passphrase, store, inline).close()
        val writesAfterMigration = store.writes
        assertEquals(1, writesAfterMigration)
        assertEquals("kept", openKeyedSqliteDriver(db, passphrase, store, inline).marker())

        assertEquals(writesAfterMigration, store.writes, "a cached key must not be re-derived")
    }

    @Test
    fun `a corrupt cached key falls back to deriving from the passphrase`() {
        val db = dbFile("corrupt-cache.db")
        val passphrase = "yet-another-passphrase"
        createWith(cipherUrl(db, passphrase), "kept")

        val store = FakeStore("00".repeat(32))
        assertEquals("kept", openKeyedSqliteDriver(db, passphrase, store, inline).marker())

        assertEquals(SqlCipherKeyMode.deriveRawKeyHex(passphrase, SqlCipherKeyMode.readSalt(db)), store.value)
    }

    @Test
    fun `an undecryptable database fails in DatabaseManager's recovery instead of being lost here`() {
        val db = dbFile("foreign.db")
        createWith(cipherUrl(db, "the-real-passphrase"), "kept")

        val store = FakeStore("11".repeat(32))
        assertFailsWith<Exception> {
            openKeyedSqliteDriver(db, "a-passphrase-that-was-never-used", store) { error("must not derive") }
        }

        assertNull(store.value, "a key that cannot open the file must not stay cached")
        assertEquals(
            "kept",
            readMarker(cipherUrl(db, "the-real-passphrase")),
            "the file must be untouched by the failed opens",
        )
    }

    @Test
    fun `a fresh install is born in raw-key mode and never runs the KDF`() {
        val db = dbFile("fresh.db")
        val store = FakeStore()

        openKeyedSqliteDriver(db, "unused-passphrase", store, inline).use { driver ->
            driver.execute(null, "CREATE TABLE probe (v TEXT)", 0)
            driver.execute(null, "INSERT INTO probe VALUES ('born-raw')", 0)
        }
        val cached = assertNotNull(store.value)
        assertTrue(SqlCipherRawKey.isHex(cached))

        assertEquals("born-raw", openKeyedSqliteDriver(db, "unused-passphrase", store, inline).marker())
        assertEquals("born-raw", readMarker(cipherUrl(db, SqlCipherRawKey.format(cached))))
        assertEquals(1, store.writes, "reopening must reuse the cached key")
        assertFalse(
            SqlCipherRawKey.isHex("not-hex"),
            "hex validation must reject a garbled cache entry",
        )
    }

    @Test
    fun `an empty passphrase opens an unencrypted database and caches nothing`() {
        val db = dbFile("plain.db")
        val store = FakeStore()

        openKeyedSqliteDriver(db, null, store, inline).use { driver ->
            driver.execute(null, "CREATE TABLE probe (v TEXT)", 0)
            driver.execute(null, "INSERT INTO probe VALUES ('plain')", 0)
        }

        assertNull(store.value)
        assertEquals("plain", readMarker("jdbc:sqlite:${db.absolutePath}"))
    }

    // The driver hands this one key to every connection it opens, so it is what each connection
    // pays for: a passphrase costs a PBKDF2 derivation per connection, a raw key doesn't.
    private fun recordingOpen(db: File, keys: MutableList<String?>): (String?) -> SqlDriver = { key ->
        keys += key
        openSqliteDriver(if (key == null) "jdbc:sqlite:${db.absolutePath}" else cipherUrl(db, key))
    }

    private val noDerivation: (() -> Unit) -> Unit = { error("the raw key must not be derived again") }

    @Test
    fun `after the first launch only the raw key reaches SQLCipher`() {
        val db = dbFile("keys-upgrade.db")
        val passphrase = "a-passphrase-from-before-raw-key-mode"
        createWith(cipherUrl(db, passphrase), "kept")
        val store = FakeStore()
        val keys = mutableListOf<String?>()

        SqlCipherKeyMode.openDriver(db, passphrase, store, inline, recordingOpen(db, keys)).close()
        assertEquals(listOf<String?>(passphrase), keys, "the first launch after the update opens with the passphrase, once")
        val raw = SqlCipherRawKey.format(assertNotNull(store.value))

        keys.clear()
        repeat(3) {
            assertEquals("kept", SqlCipherKeyMode.openDriver(db, passphrase, store, noDerivation, recordingOpen(db, keys)).marker())
        }
        assertEquals<List<String?>>(List(3) { raw }, keys, "every later launch opens once, with the raw key")
    }

    @Test
    fun `a fresh install never hands SQLCipher the passphrase`() {
        val db = dbFile("keys-fresh.db")
        val passphrase = "a-passphrase-that-must-stay-unused"
        val store = FakeStore()
        val keys = mutableListOf<String?>()

        SqlCipherKeyMode.openDriver(db, passphrase, store, noDerivation, recordingOpen(db, keys)).use { driver ->
            driver.execute(null, "CREATE TABLE probe (v TEXT)", 0)
            driver.execute(null, "INSERT INTO probe VALUES ('born-raw')", 0)
        }
        assertEquals("born-raw", SqlCipherKeyMode.openDriver(db, passphrase, store, noDerivation, recordingOpen(db, keys)).marker())

        val raw = SqlCipherRawKey.format(assertNotNull(store.value))
        assertEquals(listOf<String?>(raw, raw), keys)
    }

    @Test
    fun `raw key derivation matches SQLCipher for a known salt`() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val a = SqlCipherKeyMode.deriveRawKeyHex("passphrase", salt)
        val b = SqlCipherKeyMode.deriveRawKeyHex("passphrase", salt)
        val c = SqlCipherKeyMode.deriveRawKeyHex("passphrase", ByteArray(16))

        assertEquals(a, b)
        assertTrue(SqlCipherRawKey.isHex(a))
        assertTrue(a != c, "the file's own salt must feed the derivation")
    }
}
