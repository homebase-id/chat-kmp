package id.homebase.api.sync.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SQLiteGlobal
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(passphrase: String?): SqlDriver =
        openKeyedAndroidSqliteDriver(context, DB_FILE_NAME, passphrase, SecureStorageRawKeyStore)

    actual fun dbFilePath(): String = context.getDatabasePath(DB_FILE_NAME).absolutePath
}

internal fun openKeyedAndroidSqliteDriver(
    context: Context,
    name: String,
    passphrase: String?,
    store: RawKeyStore,
    deriveIn: (() -> Unit) -> Unit = SqlCipherKeyMode::onDaemonThread,
): SqlDriver =
    SqlCipherKeyMode.openDriver(context.getDatabasePath(name), passphrase, store, deriveIn) { key ->
        openAndroidSqliteDriver(context, name, key ?: "")
    }

/** [key] is what SQLCipher is handed: a raw `x'<hex>'` key, a passphrase, or empty for no encryption. */
internal fun openAndroidSqliteDriver(context: Context, name: String, key: String): SqlDriver {
    System.loadLibrary("sqlcipher")
    // Without the WAL flag SQLCipher's pool holds a single connection, whatever journal_mode
    // the file is in, and every read waits for the open write transaction. The size counts the
    // writer.
    SQLiteGlobal.setWALConnectionPoolSize(1 + SQLITE_READER_CONNECTIONS)
    val factory = SupportOpenHelperFactory(
        key.toByteArray(),
        ConnectionPragmasHook,
        /* enableWriteAheadLogging = */ true,
    )
    return AndroidSqliteDriver(
        schema = OdinDatabase.Schema,
        context = context,
        name = name,
        factory = factory,
        callback = object : AndroidSqliteDriver.Callback(OdinDatabase.Schema) {
            // SQLCipher resets journal_size_limit to 10 KB after the hook. In a transaction because
            // outside one SQLCipher runs a read-only PRAGMA on a reader connection; query(), not
            // execSQL, which rejects a statement returning a row.
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.beginTransaction()
                try {
                    db.query("PRAGMA journal_size_limit=$SQLITE_WRITER_JOURNAL_SIZE_LIMIT")
                        .use { it.moveToFirst() }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        },
    )
}

// Runs on every connection SQLCipher opens, readers included.
private object ConnectionPragmasHook : SQLiteDatabaseHook {
    override fun preKey(connection: SQLiteConnection) = Unit

    override fun postKey(connection: SQLiteConnection) {
        SQLITE_CONNECTION_PRAGMA_STATEMENTS.forEach { connection.executeRaw(it, null, null) }
    }
}
