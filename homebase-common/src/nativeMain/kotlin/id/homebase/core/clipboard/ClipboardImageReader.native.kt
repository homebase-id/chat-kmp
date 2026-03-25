package id.homebase.core.clipboard

actual fun getImageFromClipboard(): ByteArray? {
    // Image paste from clipboard on iOS is handled natively by the OS keyboard.
    // This function is primarily used for desktop Ctrl+V paste interception.
    return null
}
