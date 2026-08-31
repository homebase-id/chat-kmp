package id.homebase.chat.conversationlist

import io.github.vinceglb.filekit.PlatformFile

// Native: ExoPlayer/MediaMetadataRetriever read the path/content:// URI directly; nothing to revoke.
actual fun PlatformFile.toPlayableUrl(): String = toString()
actual fun revokePlayableUrl(url: String) { /* no-op on native */ }
