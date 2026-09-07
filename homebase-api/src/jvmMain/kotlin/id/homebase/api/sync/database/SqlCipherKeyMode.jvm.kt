package id.homebase.api.sync.database

import co.touchlab.kermit.Logger
import id.homebase.api.storage.SecureStorage
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Properties
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal interface RawKeyStore {
    fun get(): String?
    fun put(hex: String)
    fun remove()
}

private object SecureStorageRawKeyStore : RawKeyStore {
    private const val KEY_DB_RAW_KEY = "odin_db_raw_key"

    override fun get(): String? = SecureStorage.get(KEY_DB_RAW_KEY)
    override fun put(hex: String) = SecureStorage.put(KEY_DB_RAW_KEY, hex)
    override fun remove() = SecureStorage.remove(KEY_DB_RAW_KEY)
}

/**
 * Builds the SQLCipher JDBC URL for the desktop database, preferring **raw-key** mode
 * (`key=x'<64 hex>'`) over passphrase mode (`key=<text>`).
 *
 * The distinction is the whole point: with a passphrase SQLCipher runs
 * PBKDF2-HMAC-SHA512 × 256,000 on *every* connection open, and SQLDelight's
 * [app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver] closes its connection
 * whenever no transaction is active — so a cold start pays that KDF ~30 times
 * (measured: 165 ms each, ~4 s of a desktop launch). A raw key is the KDF's *output*,
 * so handing it over directly skips the derivation and opens in ~1 ms.
 *
 * The 32-byte key is derived once from the existing passphrase and the file's own
 * 16-byte header salt, then cached. Same key, same ciphertext — the database file is
 * never rewritten and the change is reversible by deleting the cached key.
 */
internal object SqlCipherKeyMode {
    private const val SALT_BYTES = 16
    private const val RAW_KEY_BYTES = 32
    private const val RAW_KEY_HEX_LENGTH = RAW_KEY_BYTES * 2

    // Must match the `legacy=4` (SQLCipher v4) profile the URL asks for, or the derived
    // key won't be the one the file was encrypted with.
    private const val KDF_ITERATIONS = 256_000

    private val logger = Logger.withTag("SqlCipherKeyMode")

    fun jdbcUrl(dbFile: File, passphrase: String): String =
        jdbcUrl(dbFile, passphrase, SecureStorageRawKeyStore)

    internal fun jdbcUrl(dbFile: File, passphrase: String, store: RawKeyStore): String {
        val cached = store.get()?.takeIf(::isRawKeyHex)

        if (!dbFile.isFile || dbFile.length() < SALT_BYTES) {
            // Store before the driver creates the file, so a crash in between leaves the
            // key that the next run will open it with.
            val hex = cached ?: newRawKeyHex().also { store.put(it) }
            return rawKeyUrl(dbFile, hex)
        }

        if (cached != null && opens(rawKeyUrl(dbFile, cached))) return rawKeyUrl(dbFile, cached)

        val derived = deriveRawKeyHex(passphrase, readSalt(dbFile))
        if (opens(rawKeyUrl(dbFile, derived))) {
            store.put(derived)
            logger.i { "Database migrated to SQLCipher raw-key mode" }
            return rawKeyUrl(dbFile, derived)
        }

        // Neither key decrypts the file. Hand back the passphrase URL so a genuinely
        // broken database fails exactly where it used to, in DatabaseManager's recovery.
        logger.w { "Raw-key open failed; falling back to passphrase mode" }
        store.remove()
        return passphraseUrl(dbFile, passphrase)
    }

    internal fun rawKeyUrl(dbFile: File, rawKeyHex: String): String =
        cipherUrl(dbFile, "x'$rawKeyHex'")

    internal fun passphraseUrl(dbFile: File, passphrase: String): String =
        cipherUrl(dbFile, passphrase)

    private fun cipherUrl(dbFile: File, key: String): String {
        val encoded = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
        return "jdbc:sqlite:file:${dbFile.absolutePath}?cipher=sqlcipher&legacy=4&key=$encoded"
    }

    internal fun deriveRawKeyHex(passphrase: String, salt: ByteArray): String {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, KDF_ITERATIONS, RAW_KEY_BYTES * Byte.SIZE_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(spec)
            .encoded
            .toHex()
    }

    internal fun readSalt(dbFile: File): ByteArray = dbFile.inputStream().use { stream ->
        val salt = ByteArray(SALT_BYTES)
        var read = 0
        while (read < SALT_BYTES) {
            val n = stream.read(salt, read, SALT_BYTES - read)
            if (n < 0) error("Database file shorter than its ${SALT_BYTES}-byte SQLCipher salt")
            read += n
        }
        salt
    }

    internal fun isRawKeyHex(value: String): Boolean =
        value.length == RAW_KEY_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun newRawKeyHex(): String =
        ByteArray(RAW_KEY_BYTES).also { SecureRandom().nextBytes(it) }.toHex()

    private fun opens(url: String): Boolean = try {
        DriverManager.getConnection(url, tuningProperties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM sqlite_master").close()
            }
        }
        true
    } catch (e: Exception) {
        logger.d { "Key probe rejected: ${e.message}" }
        false
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

internal fun tuningProperties(): Properties = Properties().apply {
    SQLITE_TUNING_PRAGMAS.forEach { (key, value) -> setProperty(key, value) }
}
