package id.homebase.core.clipboard

actual fun getImageFromClipboard(): ByteArray? {
    // Browser clipboard image access requires async Clipboard API which
    // is not compatible with this synchronous expect/actual signature.
    return null
}

actual suspend fun readClipboardImage(): ByteArray? = null
