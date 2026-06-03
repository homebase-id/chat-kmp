package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.PlatformFile

// iOS PlatformFile carries a real file path; the path-based send pipeline reads it directly.
// No copy needed.
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String = toString()

// Native: AVPlayer/AVAssetImageGenerator read the path directly; nothing to revoke.
actual fun PlatformFile.toPlayableUrl(): String = toString()
actual fun revokePlayableUrl(url: String) { /* no-op on native */ }
