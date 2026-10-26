package id.homebase.core.clipboard

/**
 * The text one Paste inserts. The image, when there is one, is always attached; the text beside it
 * is inserted too, except a single line that only names the image: a browser's "Copy image" adds
 * the image's address and a file manager adds its file name.
 */
fun pastedText(image: ByteArray?, text: String?): String? {
    val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (image != null && trimmed.namesAnImage()) return null
    return text
}

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff")

private fun String.namesAnImage(): Boolean {
    if ('\n' in this) return false
    if (startsWith("http://") || startsWith("https://") || startsWith("file://") || startsWith("data:image/")) return true
    return substringAfterLast('.', "").lowercase() in imageExtensions
}
