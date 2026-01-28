package id.homebase.api.sync.database

import app.cash.sqldelight.ColumnAdapter
import kotlin.uuid.Uuid

/**
 * SQLDelight ColumnAdapter for Kotlin UUID type.
 * Converts UUID to/from ByteArray for database storage.
 */
object UuidAdapter : ColumnAdapter<Uuid, ByteArray> {
    override fun decode(databaseValue: ByteArray): Uuid {
        return Uuid.fromByteArray(databaseValue)
    }

    override fun encode(value: Uuid): ByteArray {
        return value.toByteArray()
    }
}