package id.homebase.api.sync.database

import co.touchlab.kermit.Logger
import id.homebase.api.storage.SecureStorage
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal interface RawKeyStore {
    fun get(): String?
    fun put(hex: String)
    fun remove()
}

internal object SecureStorageRawKeyStore : RawKeyStore {
    private const val KEY_DB_RAW_KEY = "odin_db_raw_key"

    override fun get(): String? = SecureStorage.get(KEY_DB_RAW_KEY)
    override fun put(hex: String) = SecureStorage.put(KEY_DB_RAW_KEY, hex)
    override fun remove() = SecureStorage.remove(KEY_DB_RAW_KEY)
}

/**
 * Chooses the key SQLCipher is handed for the database on desktop and Android, preferring a
 * **raw key** (`x'<64 hex>'`) over the passphrase.
 *
 * The distinction is the whole point: with a passphrase SQLCipher runs
 * PBKDF2-HMAC-SHA512 × 256,000 on *every* connection open (165 ms on desktop, 130–300 ms on
 * an emulator, where the pool opens readers one at a time). A raw key is the KDF's *output*,
 * so handing it over directly skips the derivation and opens in a few ms.
 *
 * The 32-byte key is derived once from the existing passphrase and the file's own
 * 16-byte header salt, then cached. Same key, same ciphertext — the database file is
 * never rewritten and the change is reversible by deleting the cached key.
 */
internal object SqlCipherKeyMode {
    private const val SALT_BYTES = 16
    private const val RAW_KEY_BYTES = 32
    private const val RAW_KEY_HEX_LENGTH = RAW_KEY_BYTES * 2

    // SQLCipher v4's default KDF, which both platforms' files were encrypted with.
    private const val KDF_ITERATIONS = 256_000

    private val logger = Logger.withTag("SqlCipherKeyMode")

    /** [opens] tries a key on [dbFile] and reports whether it decrypts it. */
    fun cipherKey(
        dbFile: File,
        passphrase: String,
        store: RawKeyStore = SecureStorageRawKeyStore,
        opens: (key: String) -> Boolean,
    ): String {
        val cached = store.get()?.takeIf(::isRawKeyHex)

        if (!dbFile.isFile || dbFile.length() < SALT_BYTES) {
            // Store before the driver creates the file, so a crash in between leaves the
            // key that the next run will open it with.
            val hex = cached ?: newRawKeyHex().also { store.put(it) }
            return rawKey(hex)
        }

        if (cached != null && opens(rawKey(cached))) return rawKey(cached)

        val derived = deriveRawKeyHex(passphrase, readSalt(dbFile))
        if (opens(rawKey(derived))) {
            store.put(derived)
            logger.i { "Database migrated to SQLCipher raw-key mode" }
            return rawKey(derived)
        }

        // Neither key decrypts the file. Hand back the passphrase so a genuinely broken
        // database fails exactly where it used to, in DatabaseManager's recovery.
        logger.w { "Raw-key open failed; falling back to passphrase mode" }
        store.remove()
        return passphrase
    }

    fun rawKey(rawKeyHex: String): String = "x'$rawKeyHex'"

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

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
