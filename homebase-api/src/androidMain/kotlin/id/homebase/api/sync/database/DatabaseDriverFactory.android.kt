package id.homebase.api.sync.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(passphrase: String?): SqlDriver {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory((passphrase ?: "").toByteArray())
        return AndroidSqliteDriver(
            schema = OdinDatabase.Schema, context = context, name = DB_FILE_NAME, factory = factory
        )
    }

    actual fun dbFilePath(): String = context.getDatabasePath(DB_FILE_NAME).absolutePath
}
