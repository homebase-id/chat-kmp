package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

internal class WalFileDb(
    val driver: SqlDriver,
    val walBytes: () -> Long,
    val cleanup: () -> Unit,
)

internal expect fun openWalFileDb(): WalFileDb
