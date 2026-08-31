package id.homebase.chat.conversationlist

import io.github.vinceglb.filekit.PlatformFile

// Native: AVPlayer/AVAssetImageGenerator read the path directly; nothing to revoke.
actual fun PlatformFile.toPlayableUrl(): String = toString()
actual fun revokePlayableUrl(url: String) { /* no-op on native */ }
