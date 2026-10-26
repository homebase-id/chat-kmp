package id.homebase.api.storage

import javax.crypto.AEADBadTagException

/**
 * A failed tag check or a malformed value can never decrypt, e.g. prefs carried to a new device
 * without their Keystore key, so it counts as [SecureRead.Missing]; any other failure may pass.
 */
internal inline fun readEncrypted(load: () -> String?, decrypt: (String) -> String): SecureRead {
    val encrypted = try {
        load()
    } catch (e: Exception) {
        return SecureRead.Unavailable(e)
    } ?: return SecureRead.Missing
    return try {
        SecureRead.Found(decrypt(encrypted))
    } catch (_: AEADBadTagException) {
        SecureRead.Missing
    } catch (_: IllegalArgumentException) {
        SecureRead.Missing
    } catch (e: Exception) {
        SecureRead.Unavailable(e)
    }
}
