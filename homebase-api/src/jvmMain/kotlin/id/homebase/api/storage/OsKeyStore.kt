package id.homebase.api.storage

import java.security.MessageDigest
import java.util.Base64

internal sealed interface OsKeyRead {
    data class Found(val value: String) : OsKeyRead

    /** The store was reached and has no entry. */
    data object Absent : OsKeyRead

    /** Locked, denied or failed: the entry may exist, so it must never be treated as missing. */
    data class Unreachable(val cause: Throwable) : OsKeyRead

    /** No store exists on this machine (Linux without a Secret Service). */
    data class NoStore(val cause: Throwable) : OsKeyRead
}

internal interface OsKeyStore {
    fun read(): OsKeyRead

    /** Throws when the entry was not stored. */
    fun write(value: String)

    fun delete()

    fun resetSession() {}
}

internal const val OS_KEY_ACCOUNT = "SecureStorageKey"
private const val STORAGE_MODE_PROPERTY = "homebase.secureStorage"

internal object NoOsKeyStore : OsKeyStore {
    private val cause = IllegalStateException("OS key store disabled by $STORAGE_MODE_PROPERTY")

    override fun read(): OsKeyRead = OsKeyRead.NoStore(cause)

    override fun write(value: String) = throw cause

    override fun delete() = Unit
}

internal fun selectOsKeyStore(): OsKeyStore =
    if (System.getProperty(STORAGE_MODE_PROPERTY) == "file") NoOsKeyStore else selectNativeOsKeyStore()

// "darwin" contains "win", so mac is checked first.
internal fun selectNativeOsKeyStore(
    osName: String = System.getProperty("os.name").lowercase(),
    service: String = id.homebase.api.file.JvmFileSystemUtil.appName,
    account: String = OS_KEY_ACCOUNT,
): OsKeyStore =
    when {
        osName.contains("mac") || osName.contains("darwin") -> MacOsKeyStore(service, account)
        osName.contains("win") -> WindowsOsKeyStore(
            id.homebase.api.file.JvmFileSystemUtil.getAppDataDirectory(),
            if (account == OS_KEY_ACCOUNT) "secure_storage.key.dpapi" else "$account.dpapi",
        )
        else -> LinuxOsKeyStore(service, account)
    }

internal inline fun guardedOsRead(block: () -> OsKeyRead): OsKeyRead =
    try {
        block()
    } catch (e: Exception) {
        OsKeyRead.Unreachable(e)
    } catch (e: LinkageError) {
        OsKeyRead.Unreachable(e)
    }

private const val KEY_BYTES = 32

internal fun encodeKeyEnvelope(key: ByteArray): String =
    Base64.getEncoder().encodeToString(key) + "." + checksum(key)

/** Null when the text is not a whole, intact key; garbled reads must never pass as a key. */
internal fun decodeKeyEnvelope(text: String): ByteArray? {
    val dot = text.lastIndexOf('.')
    if (dot < 0) return null
    val key = try {
        Base64.getDecoder().decode(text.substring(0, dot))
    } catch (_: IllegalArgumentException) {
        return null
    }
    return key.takeIf { it.size == KEY_BYTES && checksum(it) == text.substring(dot + 1) }
}

private fun checksum(key: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(key).take(4).joinToString("") { "%02x".format(it) }
