package id.homebase.api.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.core.content.edit

/**
 * Android implementation of SecureStorage using Android KeyStore.
 *
 * Keys are stored in the Android KeyStore and data is encrypted with AES-GCM before being stored in
 * SharedPreferences.
 */
actual object SecureStorage {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "SecureStorageKey"
    private const val PREFS_NAME = "secure_storage_prefs"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    private lateinit var applicationContext: Context

    /**
     * Initialize the SecureStorage with application context. Must be called before using any other
     * methods.
     */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenSpec =
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()

            keyGenerator.init(keyGenSpec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun getPrefs(): SharedPreferences {
        return applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())

        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Combine IV and encrypted data
        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encryptedData: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)

        // Extract IV and encrypted bytes
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val encryptedBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    actual fun put(key: String, value: String) {
        val encryptedValue = encrypt(value)
        getPrefs().edit { putString(key, encryptedValue) }
    }

    actual fun get(key: String): String? {
        val encryptedValue = getPrefs().getString(key, null) ?: return null
        return try {
            decrypt(encryptedValue)
        } catch (e: Exception) {
            null
        }
    }

    actual fun remove(key: String) {
        getPrefs().edit { remove(key) }
    }

    actual fun contains(key: String): Boolean {
        return getPrefs().contains(key)
    }

    actual fun clear() {
        getPrefs().edit { clear() }
    }
}
