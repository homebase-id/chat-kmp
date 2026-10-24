package id.homebase.api.sync.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun SqlDriver.createMarker() = use { driver ->
        driver.execute(null, "CREATE TABLE probe(v TEXT)", 0)
        driver.execute(null, "INSERT INTO probe VALUES ('kept')", 0)
    }

    private fun SqlDriver.marker(): String? = use { driver ->
        driver.executeQuery(null, "SELECT v FROM probe", { c -> c.next(); QueryResult.Value(c.getString(0)) }, 0).value
    }

    @Test
    fun anExistingPassphraseDatabaseGetsItsRawKeyForTheNextLaunch() {
        openAndroidSqliteDriver(context, DB, PASSPHRASE).createMarker()
        val store = FakeStore()

        assertEquals("kept", openKeyedAndroidSqliteDriver(context, DB, PASSPHRASE, store) { it() }.marker())

        val raw = SqlCipherKeyMode.rawKey(assertNotNull(store.value))
        assertEquals("kept", openAndroidSqliteDriver(context, DB, raw).marker())
        assertEquals("kept", openAndroidSqliteDriver(context, DB, PASSPHRASE).marker(), "the file must not be re-encrypted")
    }

    @Test
    fun aDatabaseNoKeyOpensFailsAndForgetsTheCachedKey() {
        openAndroidSqliteDriver(context, DB, PASSPHRASE).createMarker()
        val store = FakeStore("11".repeat(32))

        assertFailsWith<Exception> {
            openKeyedAndroidSqliteDriver(context, DB, "another-passphrase", store) { error("must not derive") }
        }

        assertNull(store.value)
        assertEquals("kept", openAndroidSqliteDriver(context, DB, PASSPHRASE).marker())
    }

    @Test
    fun aFreshInstallStartsInRawKeyMode() {
        val store = FakeStore()

        openKeyedAndroidSqliteDriver(context, DB, PASSPHRASE, store) { it() }.createMarker()
        val raw = SqlCipherKeyMode.rawKey(assertNotNull(store.value))

        assertEquals("kept", openKeyedAndroidSqliteDriver(context, DB, PASSPHRASE, store) { it() }.marker())
        assertEquals("kept", openAndroidSqliteDriver(context, DB, raw).marker())
    }

    private companion object {
        const val DB = "key-mode-test.db"
        const val PASSPHRASE = "a-passphrase-that-is-not-a-raw-key"
    }
}
