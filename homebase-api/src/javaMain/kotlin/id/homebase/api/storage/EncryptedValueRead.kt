package id.homebase.api.storage

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

/** The key that sealed the stored values is gone for good, so nothing it sealed decrypts again. */
internal class SecureStorageKeyLostException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

/** A lost key, failed tag check or malformed value can never decrypt, so it is Missing; any other failure may pass, so Unavailable. */
internal inline fun readEncrypted(load: () -> String?, decrypt: (String) -> String): SecureRead {
    val encrypted = try {
        load()
    } catch (e: Exception) {
        return SecureRead.Unavailable(e)
    } ?: return SecureRead.Missing
    return try {
        SecureRead.Found(decrypt(encrypted))
    } catch (_: SecureStorageKeyLostException) {
        SecureRead.Missing
    } catch (_: AEADBadTagException) {
        SecureRead.Missing
    } catch (_: IllegalArgumentException) {
        SecureRead.Missing
    } catch (_: IndexOutOfBoundsException) {
        SecureRead.Missing
    } catch (e: Exception) {
        SecureRead.Unavailable(e)
    }
}
