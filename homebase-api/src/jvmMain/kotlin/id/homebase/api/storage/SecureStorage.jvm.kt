package id.homebase.api.storage

import id.homebase.api.file.JvmFileSystemUtil
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Desktop (JVM) implementation of SecureStorage using Java KeyStore.
 *
 * Keys are stored in a PKCS12 keystore file and data is encrypted with AES-GCM before being stored
 * in a properties file.
 */
actual object SecureStorage {
    private val storage by lazy { KeyStoreFileStorage(JvmFileSystemUtil.getAppDataDirectory()) }

    actual fun put(key: String, value: String) = storage.put(key, value)

    actual fun get(key: String): String? = (read(key) as? SecureRead.Found)?.value

    actual fun read(key: String): SecureRead = storage.read(key)

    actual fun remove(key: String) = storage.remove(key)

    actual fun contains(key: String): Boolean = storage.contains(key)

    actual fun clear() = storage.clear()
}

internal class KeyStoreFileStorage(storageDir: File) {
    private companion object {
        const val KEYSTORE_FILE = "secure_storage.p12"
        const val DATA_FILE = "secure_storage.properties"
        const val KEY_ALIAS = "SecureStorageKey"
        const val KEYSTORE_PASSWORD =
            "SecureStorageKeyStorePassword" // In production, derive from machine-specific data
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH = 128
        const val GCM_IV_LENGTH = 12
    }

    private val keyStoreFile = File(storageDir, KEYSTORE_FILE)
    private val dataFile = File(storageDir, DATA_FILE)

    private fun loadKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")

        if (keyStoreFile.exists()) {
            keyStoreFile.inputStream().use { stream ->
                keyStore.load(stream, KEYSTORE_PASSWORD.toCharArray())
            }
        } else {
            keyStore.load(null, KEYSTORE_PASSWORD.toCharArray())
        }

        return keyStore
    }

    private fun saveKeyStore(keyStore: KeyStore) {
        keyStoreFile.outputStream().use { stream ->
            keyStore.store(stream, KEYSTORE_PASSWORD.toCharArray())
        }
    }

    // The password is a constant, so an unrecoverable or non-secret entry stays that way.
    private fun storedKey(keyStore: KeyStore): SecretKey {
        if (!keyStore.containsAlias(KEY_ALIAS)) throw SecureStorageKeyLostException("$KEY_ALIAS is not in $keyStoreFile")
        val key = try {
            keyStore.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray())
        } catch (e: UnrecoverableKeyException) {
            throw SecureStorageKeyLostException("$KEY_ALIAS can't be recovered", e)
        }
        return key as? SecretKey ?: throw SecureStorageKeyLostException("$KEY_ALIAS is not a secret key")
    }

    private fun keyForEncrypt(): SecretKey {
        val keyStore = loadKeyStore()
        return try {
            storedKey(keyStore)
        } catch (_: SecureStorageKeyLostException) {
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(256, SecureRandom())
            keyGenerator.generateKey().also { secretKey ->
                keyStore.setKeyEntry(KEY_ALIAS, secretKey, KEYSTORE_PASSWORD.toCharArray(), null)
                saveKeyStore(keyStore)
            }
        }
    }

    private fun loadProperties(): Properties {
        val properties = Properties()
        if (dataFile.exists()) {
            dataFile.inputStream().use { stream -> properties.load(stream) }
        }
        return properties
    }

    private fun saveProperties(properties: Properties) {
        dataFile.outputStream().use { stream -> properties.store(stream, "SecureStorage Data") }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForEncrypt())

        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Combine IV and encrypted data
        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decrypt(encryptedData: String): String {
        val combined = Base64.getDecoder().decode(encryptedData)

        // Extract IV and encrypted bytes
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val encryptedBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        try {
            cipher.init(Cipher.DECRYPT_MODE, storedKey(loadKeyStore()), spec)
        } catch (e: InvalidKeyException) {
            throw SecureStorageKeyLostException("$KEY_ALIAS no longer fits $TRANSFORMATION", e)
        }

        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun put(key: String, value: String) {
        val encryptedValue = encrypt(value)
        val properties = loadProperties()
        properties.setProperty(key, encryptedValue)
        saveProperties(properties)
    }

    fun read(key: String): SecureRead =
        readEncrypted({ loadProperties().getProperty(key) }, ::decrypt)

    fun remove(key: String) {
        val properties = loadProperties()
        properties.remove(key)
        saveProperties(properties)
    }

    fun contains(key: String): Boolean {
        val properties = loadProperties()
        return properties.containsKey(key)
    }

    fun clear() {
        val properties = Properties()
        saveProperties(properties)
    }
}
