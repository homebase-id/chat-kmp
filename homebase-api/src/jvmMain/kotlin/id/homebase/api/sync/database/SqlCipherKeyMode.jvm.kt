package id.homebase.api.sync.database

import co.touchlab.kermit.Logger
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.util.Properties

/** The SQLCipher JDBC URL for [dbFile], in raw-key mode when it can be (see [SqlCipherKeyMode]). */
internal fun sqlCipherJdbcUrl(
    dbFile: File,
    passphrase: String,
    store: RawKeyStore = SecureStorageRawKeyStore,
): String = cipherUrl(dbFile, SqlCipherKeyMode.cipherKey(dbFile, passphrase, store) { opens(cipherUrl(dbFile, it)) })

internal fun rawKeyJdbcUrl(dbFile: File, rawKeyHex: String): String =
    cipherUrl(dbFile, SqlCipherKeyMode.rawKey(rawKeyHex))

// `legacy=4` must match SqlCipherKeyMode's KDF parameters, or a derived key won't be the one
// the file was encrypted with.
internal fun cipherUrl(dbFile: File, key: String): String {
    val encoded = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
    return "jdbc:sqlite:file:${dbFile.absolutePath}?cipher=sqlcipher&legacy=4&key=$encoded"
}

private fun opens(url: String): Boolean = try {
    DriverManager.getConnection(url, tuningProperties()).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM sqlite_master").close()
        }
    }
    true
} catch (e: Exception) {
    Logger.withTag("SqlCipherKeyMode").d { "Key probe rejected: ${e.message}" }
    false
}

internal fun tuningProperties(): Properties = Properties().apply {
    setProperty("journal_mode", SQLITE_JOURNAL_MODE)
    SQLITE_CONNECTION_PRAGMAS.forEach { (key, value) -> setProperty(key, value) }
}
