package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import java.io.File
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.concurrent.thread
import kotlin.time.TimeSource

/**
 * Opens the SQLCipher database on desktop and Android with a raw key (`x'<64 hex>'`) instead of
 * the passphrase, so SQLCipher skips its PBKDF2 on every connection. The key is derived once
 * from the passphrase and the file's 16-byte salt, then cached; the file is never rewritten.
 */
internal object SqlCipherKeyMode {
    private const val SALT_BYTES = 16

    // SQLCipher v4's default KDF, which both platforms' files were encrypted with.
    private const val KDF_ITERATIONS = 256_000

    private val logger = Logger.withTag("SqlCipherKeyMode")

    /**
     * [open] builds the driver for a key, or for no encryption when it gets null (an empty
     * [passphrase]). Without a cached raw key that decrypts the file, this launch opens with the
     * passphrase and [deriveIn] derives the raw key for the next one: Android's PBKDF2 runs in
     * Java, 3.3 s on an emulator. A file the passphrase can't open fails in DatabaseManager's
     * recovery as before.
     */
    fun openDriver(
        dbFile: File,
        passphrase: String?,
        store: RawKeyStore,
        deriveIn: (() -> Unit) -> Unit,
        open: (key: String?) -> SqlDriver,
    ): SqlDriver {
        if (passphrase.isNullOrEmpty()) return open(null).verified()
        val cached = store.get()?.takeIf(SqlCipherRawKey::isHex)

        if (!dbFile.isFile || dbFile.length() < SALT_BYTES) {
            // Store before the driver creates the file, so a crash in between leaves the
            // key that the next run will open it with.
            val hex = cached ?: SqlCipherRawKey.newHex().also(store::put)
            return open(SqlCipherRawKey.format(hex)).verified()
        }

        if (cached != null) {
            tryOpen(open, SqlCipherRawKey.format(cached))?.let { return it }
            logger.w { "Cached raw key doesn't open the database; using the passphrase" }
            store.remove()
        }

        val driver = open(passphrase).verified()
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
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, KDF_ITERATIONS, SqlCipherRawKey.BYTES * Byte.SIZE_BITS)
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
}
