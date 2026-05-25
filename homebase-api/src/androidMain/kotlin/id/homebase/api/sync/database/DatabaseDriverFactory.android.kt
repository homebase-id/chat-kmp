package id.homebase.api.sync.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(passphrase: String?): SqlDriver {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory((passphrase ?: "").toByteArray())
        // Reader concurrency note: unlike iOS (NativeSqliteDriver maxReaderConnections=4),
        // SupportOpenHelperFactory exposes no pool-size knob — the SQLCipher WAL connection
        // pool is sized by the platform. WAL (enabled in onConfigure below) is what allows
        // concurrent readers; DatabaseManager.READ_PARALLELISM is kept at 4 to match iOS and
        // not over-admit readers. If the pool serves fewer, the surplus reads wait in
        // SQLiteConnectionPool.waitForConnection (off the Main thread, since all reads route
        // through the read lane) — see READ_PARALLELISM for the verification signal.
        return AndroidSqliteDriver(
            schema = OdinDatabase.Schema,
            context = context,
            name = DB_FILE_NAME,
            factory = factory,
            // Apply the shared SQLITE_TUNING_PRAGMAS in onConfigure (no transaction active
            // yet). Same set as desktop/iOS. SQLCipher 4.x supports WAL.
            //
            // Use query() + realize the cursor, NOT execSQL(): SQLCipher's execSQL rejects
            // any statement that returns a row ("Queries can be performed using ... query or
            // rawQuery methods only"), and `PRAGMA journal_mode=WAL` / `busy_timeout` return
            // their resulting value. query() permits result rows; moveToFirst() forces the
            // pragma to actually execute (the cursor is otherwise realized lazily).
            callback = object : AndroidSqliteDriver.Callback(OdinDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    sqliteTuningPragmaStatements().forEach { pragma ->
                        db.query(pragma).use { it.moveToFirst() }
                    }
                }
            },
        )
    }

    actual fun dbFilePath(): String = context.getDatabasePath(DB_FILE_NAME).absolutePath
}
