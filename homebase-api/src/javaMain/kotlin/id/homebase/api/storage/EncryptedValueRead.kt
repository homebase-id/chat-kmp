package id.homebase.api.storage

import javax.crypto.AEADBadTagException

/** A failed tag check or malformed value can never decrypt, so it is Missing; any other failure may pass, so Unavailable. */
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
