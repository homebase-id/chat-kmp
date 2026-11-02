package id.homebase.api.storage

import co.touchlab.kermit.Logger
import id.homebase.api.file.JvmFileSystemUtil
import id.homebase.api.sync.database.DatabaseUnavailableException
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop (JVM) implementation of SecureStorage: values are sealed with AES-GCM in a properties file,
 * and the AES key lives in the OS store (macOS Keychain, Windows DPAPI, Linux Secret Service).
 * Without a Secret Service the key stays in a 0600 PKCS12 file.
 */
actual object SecureStorage {
    private val storage by lazy { KeyStoreFileStorage(JvmFileSystemUtil.getAppDataDirectory(), selectOsKeyStore()) }

    actual fun put(key: String, value: String) = storage.put(key, value)

    actual fun get(key: String): String? = (read(key) as? SecureRead.Found)?.value

    actual fun read(key: String): SecureRead = storage.read(key)

    actual fun remove(key: String) = storage.remove(key)

    actual fun contains(key: String): Boolean = storage.contains(key)

    actual fun clear() = storage.clear()
}

internal class OsKeyStoreUnavailableException(cause: Throwable) : DatabaseUnavailableException("OS key store is unreachable", cause)

internal class KeyStoreFileStorage(
    storageDir: File,
    private val osKeyStore: OsKeyStore,
) {
    private companion object {
        const val KEYSTORE_FILE = "secure_storage.p12"
        const val DATA_FILE = "secure_storage.properties"
        const val KEY_ALIAS = "SecureStorageKey"
        const val KEYSTORE_PASSWORD = "SecureStorageKeyStorePassword"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH = 128
        const val GCM_IV_LENGTH = 12
        const val READ_ATTEMPTS = 3
        const val TAG = "SecureStorage"
    }

    private val keyStoreFile = File(storageDir, KEYSTORE_FILE)
    private val dataFile = File(storageDir, DATA_FILE)
    private val lock = Any()

    private var cachedKey: SecretKey? = null

    var usingFallbackFile = false
        private set

    private sealed interface FileKey {
        data object None : FileKey

        data object Lost : FileKey

        data class Valid(val key: SecretKey) : FileKey
    }

    private fun loadKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")
        if (keyStoreFile.exists()) {
            keyStoreFile.inputStream().use { stream -> keyStore.load(stream, KEYSTORE_PASSWORD.toCharArray()) }
        } else {
            keyStore.load(null, KEYSTORE_PASSWORD.toCharArray())
        }
        return keyStore
    }

    private fun saveKeyStore(keyStore: KeyStore) {
        createRestricted(keyStoreFile)
        keyStoreFile.outputStream().use { stream -> keyStore.store(stream, KEYSTORE_PASSWORD.toCharArray()) }
    }

    private fun createRestricted(file: File) {
        if (file.exists()) {
            restrict(file)
            return
        }
        try {
            Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            // not a POSIX file system; the OS profile already restricts the folder
        }
    }

    private fun restrict(file: File) {
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // not a POSIX file system
        }
    }

    private fun readFileKey(): FileKey {
        if (!keyStoreFile.exists()) return FileKey.None
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(KEY_ALIAS)) return FileKey.Lost
        val key = try {
            keyStore.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray())
        } catch (_: UnrecoverableKeyException) {
            return FileKey.Lost
        }
        return (key as? SecretKey)?.let(FileKey::Valid) ?: FileKey.Lost
    }

    private fun writeFileKey(key: SecretKey) {
        val keyStore = loadKeyStore()
        keyStore.setKeyEntry(KEY_ALIAS, key, KEYSTORE_PASSWORD.toCharArray(), null)
        saveKeyStore(keyStore)
    }

    private fun deleteFileKey() {
        keyStoreFile.delete()
    }

    private fun loadProperties(): Properties {
        val properties = Properties()
        if (dataFile.exists()) {
            dataFile.inputStream().use { stream -> properties.load(stream) }
        }
        return properties
    }

    private fun saveProperties(properties: Properties) {
        createRestricted(dataFile)
        dataFile.outputStream().use { stream -> properties.store(stream, "SecureStorage Data") }
    }

    private fun newKey(): SecretKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    private fun osRead(): OsKeyRead {
        repeat(READ_ATTEMPTS) {
            val result = try {
                osKeyStore.read()
            } catch (e: Exception) {
                OsKeyRead.Unreachable(e)
            } catch (e: LinkageError) {
                OsKeyRead.NoStore(e)
            }
            if (result !is OsKeyRead.Found) {
                if (result is OsKeyRead.Unreachable) osKeyStore.resetSession()
                return result
            }
            if (decodeKeyEnvelope(result.value) != null) return result
            osKeyStore.resetSession()
        }
        return OsKeyRead.Unreachable(IllegalStateException("OS key store returned an invalid key"))
    }

    private fun toKey(envelope: String): SecretKey = SecretKeySpec(decodeKeyEnvelope(envelope)!!, "AES")

    /** Stores and reads back; true only when the store now holds exactly [key]. */
    private fun persistKey(key: SecretKey): Boolean =
        attemptOs("write the key to") {
            osKeyStore.write(encodeKeyEnvelope(key.encoded))
            (osKeyStore.read() as? OsKeyRead.Found)?.value?.let(::decodeKeyEnvelope)?.contentEquals(key.encoded) == true
        }

    private inline fun attemptOs(action: String, block: () -> Boolean): Boolean =
        try {
            block()
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "Could not $action the OS key store" }
            false
        } catch (e: LinkageError) {
            Logger.w(tag = TAG, throwable = e) { "Could not $action the OS key store" }
            false
        }

    private fun decryptsAny(key: SecretKey, properties: Properties): Boolean =
        properties.stringPropertyNames().any { name ->
            try {
                decryptWith(key, properties.getProperty(name))
                true
            } catch (_: Exception) {
                false
            }
        }

    private fun resolveKey(create: Boolean): SecretKey {
        cachedKey?.let { return it }
        val fileKey = readFileKey()
        val key = when (val os = osRead()) {
            is OsKeyRead.Found -> resolveFound(toKey(os.value), fileKey)
            is OsKeyRead.Absent -> resolveAbsent(fileKey, create)
            is OsKeyRead.Unreachable ->
                (fileKey as? FileKey.Valid)?.key ?: throw OsKeyStoreUnavailableException(os.cause)
            is OsKeyRead.NoStore -> resolveNoStore(os, fileKey, create)
        }
        cachedKey = key
        return key
    }

    private fun resolveFound(storeKey: SecretKey, fileKey: FileKey): SecretKey {
        if (fileKey !is FileKey.Valid) return storeKey
        if (fileKey.key.encoded.contentEquals(storeKey.encoded)) {
            deleteFileKey()
            return storeKey
        }
        val properties = loadProperties()
        val candidates = if (properties.isEmpty) listOf(fileKey.key, storeKey) else listOf(storeKey, fileKey.key)
        val chosen = candidates.firstOrNull { properties.isEmpty || decryptsAny(it, properties) } ?: storeKey
        if (chosen === storeKey) {
            deleteFileKey()
        } else if (persistKey(chosen)) {
            deleteFileKey()
        }
        return chosen
    }

    private fun resolveAbsent(fileKey: FileKey, create: Boolean): SecretKey {
        if (fileKey is FileKey.Valid) {
            if (persistKey(fileKey.key)) deleteFileKey() else Logger.w(tag = TAG) { "Key migration failed; keeping the key file and retrying next start" }
            return fileKey.key
        }
        if (!create) throw SecureStorageKeyLostException("no key in the OS key store")
        val fresh = newKey()
        if (!persistKey(fresh)) throw OsKeyStoreUnavailableException(IllegalStateException("new key could not be stored"))
        return fresh
    }

    private fun resolveNoStore(noStore: OsKeyRead.NoStore, fileKey: FileKey, create: Boolean): SecretKey {
        usingFallbackFile = true
        Logger.w(tag = TAG, throwable = noStore.cause) { "No OS key store available; keeping the key in a 0600 file" }
        return when (fileKey) {
            is FileKey.Valid -> fileKey.key.also { restrict(keyStoreFile) }
            is FileKey.Lost -> if (create) newKey().also(::writeFileKey) else throw SecureStorageKeyLostException("key file has no usable key")
            is FileKey.None -> when {
                !loadProperties().isEmpty -> throw OsKeyStoreUnavailableException(noStore.cause)
                create -> newKey().also(::writeFileKey)
                else -> throw SecureStorageKeyLostException("no key")
            }
        }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, resolveKey(create = true))
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encryptedBytes)
    }

    private fun decryptWith(key: SecretKey, encryptedData: String): String {
        val combined = Base64.getDecoder().decode(encryptedData)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val encryptedBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        } catch (e: InvalidKeyException) {
            throw SecureStorageKeyLostException("$KEY_ALIAS no longer fits $TRANSFORMATION", e)
        }
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    private fun decrypt(encryptedData: String): String = decryptWith(resolveKey(create = false), encryptedData)

    fun put(key: String, value: String) = synchronized(lock) {
        val encryptedValue = guardLinkage { encrypt(value) }
        val properties = loadProperties()
        properties.setProperty(key, encryptedValue)
        saveProperties(properties)
    }

    fun read(key: String): SecureRead = synchronized(lock) {
        readEncrypted({ loadProperties().getProperty(key) }) { encrypted -> guardLinkage { decrypt(encrypted) } }
    }

    fun remove(key: String) = synchronized(lock) {
        val properties = loadProperties()
        properties.remove(key)
        saveProperties(properties)
        if (properties.isEmpty) deleteKeys()
    }

    fun contains(key: String): Boolean = synchronized(lock) { loadProperties().containsKey(key) }

    fun clear() = synchronized(lock) {
        saveProperties(Properties())
        deleteKeys()
    }

    private fun deleteKeys() {
        cachedKey = null
        attemptOs("delete the key from") { osKeyStore.delete(); true }
        deleteFileKey()
    }

    // Errors skip readEncrypted's Exception handlers, so a missing native library would crash instead of reading as unavailable.
    private inline fun <T> guardLinkage(block: () -> T): T =
        try {
            block()
        } catch (e: LinkageError) {
            throw OsKeyStoreUnavailableException(e)
        }
}
