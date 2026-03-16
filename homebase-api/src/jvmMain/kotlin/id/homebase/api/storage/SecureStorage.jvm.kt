package id.homebase.api.storage

import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
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
    private const val KEYSTORE_FILE = "secure_storage.p12"
    private const val DATA_FILE = "secure_storage.properties"
    private const val KEY_ALIAS = "SecureStorageKey"
    private const val KEYSTORE_PASSWORD =
        "SecureStorageKeyStorePassword" // In production, derive from machine-specific data
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    private val storageDir: File by lazy {
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".homebase-chat")
        appDir.mkdirs()
        appDir
    }

    private val keyStoreFile: File by lazy { File(storageDir, KEYSTORE_FILE) }
    private val dataFile: File by lazy { File(storageDir, DATA_FILE) }

    private fun getOrCreateKeyStore(): KeyStore {
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

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = getOrCreateKeyStore()

        return if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as SecretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(256, SecureRandom())
            val secretKey = keyGenerator.generateKey()

            keyStore.setKeyEntry(KEY_ALIAS, secretKey, KEYSTORE_PASSWORD.toCharArray(), null)
            saveKeyStore(keyStore)

            secretKey
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
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())

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
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)

        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    actual fun put(key: String, value: String) {
        val encryptedValue = encrypt(value)
        val properties = loadProperties()
        properties.setProperty(key, encryptedValue)
        saveProperties(properties)
    }

    actual fun get(key: String): String? {
        val properties = loadProperties()
        val encryptedValue = properties.getProperty(key) ?: return null
        return try {
            decrypt(encryptedValue)
        } catch (e: Exception) {
            null
        }
    }

    actual fun remove(key: String) {
        val properties = loadProperties()
        properties.remove(key)
        saveProperties(properties)
    }

    actual fun contains(key: String): Boolean {
        val properties = loadProperties()
        return properties.containsKey(key)
    }

    actual fun clear() {
        val properties = Properties()
        saveProperties(properties)
    }
}
