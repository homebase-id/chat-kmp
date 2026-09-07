package id.homebase.chat.conversationlist

import io.github.vinceglb.filekit.PlatformFile

// Native: the real path is already okio/VLCJ-readable; no blob URL to mint or revoke.
actual fun PlatformFile.toPlayableUrl(): String = toString()
actual fun revokePlayableUrl(url: String) { /* no-op on native */ }
