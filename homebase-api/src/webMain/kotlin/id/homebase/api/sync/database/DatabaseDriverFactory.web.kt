package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        TODO("Not yet implemented")
    }

    actual fun dbFilePath(): String {
        TODO("Not yet implemented")
    }
}
