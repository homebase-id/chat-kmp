package id.homebase.core.clipboard

actual fun getImageFromClipboard(): ByteArray? {
    // Image paste from clipboard on Android is handled natively by the OS keyboard.
    // This function is primarily used for desktop Ctrl+V paste interception.
    return null
}
