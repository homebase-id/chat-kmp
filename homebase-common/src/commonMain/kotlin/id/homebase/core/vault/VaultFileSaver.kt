package id.homebase.core.vault

/**
 * Adds a file that is already on this device to the user's Vault as a new entry.
 * Write-only, so the Vault doesn't need to be unlocked. The upload runs in the app scope and
 * outlives the caller. [localPath] must be an UploadTemp export; it is always deleted before this returns.
 * Returns false when the Vault isn't activated or the upload couldn't be enqueued.
 */
interface VaultFileSaver {
    suspend fun save(localPath: String, contentType: String, entryName: String): Boolean
}
