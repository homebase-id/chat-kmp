package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name

// Native PlatformFile carries a real path / content:// URI; the existing path-based send
// pipeline (and resolveToFilePath for content URIs) already handles it. No copy needed at send time.
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String = toString()

// Copy the picked file into the sandbox cache dir at pick time, mirroring the working Vault upload
// contract. On Android the picked file is a content:// URI whose read grant can be transient;
// copying through FileKit's copyTo (which reads the content URI) yields a stable plain file the
// send path can always read — equivalent to the downstream resolveToFilePath copy, just earlier.
actual suspend fun PlatformFile.materializeForUpload(fileOps: FileOperationsProvider): PlatformFile {
    val dest = PlatformFile(FileKit.cacheDir, sandboxCopyName(name, mimeType()?.toString()))
    copyTo(dest)
    return dest
}

// Native: ExoPlayer/MediaMetadataRetriever read the path/content:// URI directly; nothing to revoke.
actual fun PlatformFile.toPlayableUrl(): String = toString()
actual fun revokePlayableUrl(url: String) { /* no-op on native */ }
