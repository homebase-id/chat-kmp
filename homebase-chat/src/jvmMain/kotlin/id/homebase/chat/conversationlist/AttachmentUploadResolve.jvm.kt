package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.name
import kotlin.uuid.Uuid

// Desktop PlatformFile carries a real filesystem path; the path-based send pipeline reads it
// directly. No copy needed at send time.
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String = toString()

// Copy the picked file into the sandbox cache dir at pick time, mirroring the working Vault upload
// contract so all native platforms behave the same. Desktop has no security scope, but copying a
// real filesystem path is cheap and harmless and keeps the send path reading a sandbox file.
actual suspend fun PlatformFile.materializeForUpload(fileOps: FileOperationsProvider): PlatformFile {
    val dest = PlatformFile(FileKit.cacheDir, "chat_attach_${Uuid.random()}_$name")
    copyTo(dest)
    return dest
}

// Native: the real path is already okio/VLCJ-readable; no blob URL to mint or revoke.
actual fun PlatformFile.toPlayableUrl(): String = toString()
actual fun revokePlayableUrl(url: String) { /* no-op on native */ }
