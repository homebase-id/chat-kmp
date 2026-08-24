package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name

// iOS PlatformFile carries a real file path; the path-based send pipeline reads it directly.
// No copy needed at send time — the scoped copy happened at pick time (materializeForUpload).
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String = toString()

// Copy the picked file into the sandbox cache dir WHILE the picker's security scope is still live
// (this runs in the picker-callback launch before any further suspension). FileKit's copyTo opens
// the source through its NSURL — which still holds the scope grant here — and writes a plain
// sandbox file, so the later scope-less path read in the send path always succeeds.
// Keep the original name (plus a MIME-derived extension when it has none) so the send path's
// content-type / display-name resolution still works; prefix with a uuid so concurrent picks
// can't collide. See sandboxCopyName.
actual suspend fun PlatformFile.materializeForUpload(fileOps: FileOperationsProvider): PlatformFile {
    val dest = PlatformFile(FileKit.cacheDir, sandboxCopyName(name, mimeType()?.toString()))
    copyTo(dest)
    return dest
}

// Native: AVPlayer/AVAssetImageGenerator read the path directly; nothing to revoke.
actual fun PlatformFile.toPlayableUrl(): String = toString()
actual fun revokePlayableUrl(url: String) { /* no-op on native */ }
