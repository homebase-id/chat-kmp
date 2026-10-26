package id.homebase.core.clipboard

/** Web-only: the browser clipboard can only be read asynchronously. Other platforms return null; they read text synchronously. */
expect suspend fun readClipboardText(): String?
