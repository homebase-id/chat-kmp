package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.storage.SecureRead
import id.homebase.api.storage.SecureStorage
import java.io.File
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.concurrent.thread
import kotlin.time.TimeSource

internal interface RawKeyStore {
    fun read(): SecureRead
    fun put(hex: String)
}

internal object SecureStorageRawKeyStore : RawKeyStore {
    private const val KEY_DB_RAW_KEY = "odin_db_raw_key"

    override fun read(): SecureRead = SecureStorage.read(KEY_DB_RAW_KEY)
    override fun put(hex: String) = SecureStorage.put(KEY_DB_RAW_KEY, hex)
}

/**
 * Opens the SQLCipher database on desktop and Android with a raw key (`x'<64 hex>'`) instead of
 * the passphrase, so SQLCipher skips its PBKDF2 on every connection. The key is derived once
 * from the passphrase and the file's 16-byte salt, then cached; the file is never rewritten.
 */
internal object SqlCipherKeyMode {
    private const val SALT_BYTES = 16
    private const val RAW_KEY_BYTES = 32
    private const val RAW_KEY_HEX_LENGTH = RAW_KEY_BYTES * 2

    // SQLCipher v4's default KDF, which both platforms' files were encrypted with.
    private const val KDF_ITERATIONS = 256_000

    private val logger = Logger.withTag("SqlCipherKeyMode")

    /**
     * [open] builds the driver for a key, or for no encryption when it gets null (an empty
     * [passphrase]). Without a cached raw key that decrypts the file, this launch opens with the
     * passphrase and [deriveIn] derives the raw key for the next one: Android's PBKDF2 runs in
     * Java, 3.3 s on an emulator. A file neither key opens fails in DatabaseManager's recovery,
     * unless the stored key couldn't be read: then it throws [DatabaseUnavailableException] and
     * nothing is written. A stored key is only ever replaced by one the passphrase proved.
     */
    fun openDriver(
        dbFile: File,
        passphrase: String?,
        store: RawKeyStore,
        deriveIn: (() -> Unit) -> Unit,
        open: (key: String?) -> SqlDriver,
    ): SqlDriver {
        if (passphrase.isNullOrEmpty()) return open(null).verified()
        val stored = store.read()
        val unavailable = (stored as? SecureRead.Unavailable)?.cause
        val cached = (stored as? SecureRead.Found)?.value?.takeIf(::isRawKeyHex)

        if (!dbFile.isFile || dbFile.length() < SALT_BYTES) {
            if (unavailable != null) throw DatabaseUnavailableException("Raw key unreadable", unavailable)
            // Store before the driver creates the file, so a crash in between leaves the
            // key that the next run will open it with.
            val hex = cached ?: ByteArrayUtil.getRndByteArray(RAW_KEY_BYTES).toHexString().also(store::put)
            return open(rawKey(hex)).verified()
        }

        if (cached != null) {
            tryOpen(open, rawKey(cached))?.let { return it }
            logger.w { "Cached raw key doesn't open the database; using the passphrase" }
        }

        val driver = try {
            open(passphrase).verified()
        } catch (e: Exception) {
            if (unavailable != null) throw DatabaseUnavailableException("Raw key unreadable and the passphrase doesn't open the database", e)
            throw e
        }
        if (unavailable != null) return driver
        deriveIn {
            val started = TimeSource.Monotonic.markNow()
            store.put(deriveRawKeyHex(passphrase, readSalt(dbFile)))
            logger.i { "Derived the database's raw key in ${started.elapsedNow().inWholeMilliseconds} ms, used from the next launch" }
        }
        return driver
    }

    fun onDaemonThread(block: () -> Unit) {
        thread(isDaemon = true, name = "sqlcipher-raw-key") {
            try {
                block()
            } catch (e: Exception) {
                logger.w(e) { "Deriving the raw key failed; the next launch tries again" }
            }
        }
    }

    fun rawKey(rawKeyHex: String): String = "x'$rawKeyHex'"

    private fun tryOpen(open: (key: String?) -> SqlDriver, key: String): SqlDriver? = try {
        open(key).verified()
    } catch (e: Exception) {
        logger.d { "Key rejected: ${e.message}" }
        null
    }

    // Drivers open lazily; SQLCipher only finds out a key is wrong when it first reads the file.
    private fun SqlDriver.verified(): SqlDriver = try {
        executeQuery(null, "SELECT count(*) FROM sqlite_master", { QueryResult.Value(Unit) }, 0)
        this
    } catch (e: Exception) {
        close()
        throw e
    }

    internal fun deriveRawKeyHex(passphrase: String, salt: ByteArray): String {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, KDF_ITERATIONS, RAW_KEY_BYTES * Byte.SIZE_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(spec)
            .encoded
            .toHexString()
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
}
