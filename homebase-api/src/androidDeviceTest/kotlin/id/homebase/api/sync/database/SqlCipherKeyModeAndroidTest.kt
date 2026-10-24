package id.homebase.api.sync.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android half of SqlCipherKeyModeTest: Android's PBKDF2 must derive the key SQLCipher-android
 * encrypted the file with, or every existing install falls back to the slow passphrase.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherKeyModeAndroidTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbFile = context.getDatabasePath(DB)

    private class FakeStore(var value: String? = null) : RawKeyStore {
        override fun get(): String? = value
        override fun put(hex: String) {
            value = hex
        }

        override fun remove() {
            value = null
        }
    }

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(DB)
    }

    private fun createWith(key: String) = openAndroidSqliteDriver(context, DB, key).use { driver ->
        driver.execute(null, "CREATE TABLE probe(v TEXT)", 0)
        driver.execute(null, "INSERT INTO probe VALUES ('kept')", 0)
    }

    private fun readMarker(key: String): String? = openAndroidSqliteDriver(context, DB, key).use { driver ->
        driver.executeQuery(null, "SELECT v FROM probe", { c -> c.next(); QueryResult.Value(c.getString(0)) }, 0).value
    }

    @Test
    fun anExistingPassphraseDatabaseOpensWithItsDerivedRawKey() {
        createWith(PASSPHRASE)
        val store = FakeStore()

        val key = sqlCipherKey(dbFile, PASSPHRASE, store)

        assertEquals(SqlCipherKeyMode.rawKey(assertNotNull(store.value)), key)
        assertEquals("kept", readMarker(key))
        assertEquals("kept", readMarker(PASSPHRASE), "the file must not be re-encrypted")
    }

    @Test
    fun aDatabaseNoKeyOpensFallsBackToThePassphrase() {
        createWith(PASSPHRASE)
        val store = FakeStore("11".repeat(32))

        assertEquals("another-passphrase", sqlCipherKey(dbFile, "another-passphrase", store))
        assertNull(store.value)
        assertEquals("kept", readMarker(PASSPHRASE))
    }

    @Test
    fun aFreshInstallStartsInRawKeyMode() {
        val store = FakeStore()

        val key = sqlCipherKey(dbFile, PASSPHRASE, store)
        createWith(key)

        assertEquals(SqlCipherKeyMode.rawKey(assertNotNull(store.value)), key)
        assertEquals(key, sqlCipherKey(dbFile, PASSPHRASE, store))
        assertEquals("kept", readMarker(key))
    }

    private companion object {
        const val DB = "key-mode-test.db"
        const val PASSPHRASE = "a-passphrase-that-is-not-a-raw-key"
    }
}
