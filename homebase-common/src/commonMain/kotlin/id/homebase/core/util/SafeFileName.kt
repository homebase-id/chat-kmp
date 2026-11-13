package id.homebase.core.util

import id.homebase.api.util.toNfc

private const val MAX_BYTES = 255
private const val MAX_EXTENSION_CHARS = 32
private const val RESERVED = "<>:\"/\\|?*"
private val WINDOWS_DEVICES = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

/**
 * A file name that every platform we save to accepts, Windows being the strictest, and that can't leave
 * its folder: one path segment, no control or reserved characters, not a Windows device name, no
 * leading dot (a hidden file) and no trailing dot or space (Windows drops them), NFC, and at most 255
 * UTF-8 bytes, cut from the stem so the extension survives. [extension], when given, replaces the
 * name's own. [fallback] stands in for a name with nothing left.
 */
fun safeFileName(raw: String?, fallback: String = "file", extension: String? = null): String {
    val name = lastSegment(raw)
    val (stem, ext) = if (extension != null) name to clean(extension).let { if (it.isEmpty()) "" else ".$it" } else split(name)
    return fit(stem.ifEmpty { fallback }, ext, fallback)
}

/** [safeFileName] of a payload saved to the device: the sender's name, else [fallbackStem], with an extension from [contentType] when the name has none. */
fun saveFileName(name: String?, fallbackStem: String, contentType: String?): String {
    if (split(lastSegment(name)).second.isNotEmpty()) return safeFileName(name, fallbackStem)
    val extension = contentType?.let { extensionForMimeType(it) }
        ?: contentType?.substringAfter("/")?.takeIf { it != "octet-stream" && '.' !in it && '+' !in it }
        ?: "bin"
    return safeFileName(name, fallbackStem, extension)
}

private fun lastSegment(raw: String?): String = clean(raw.orEmpty().substringAfterLast('/').substringAfterLast('\\'))

private fun clean(name: String): String =
    name.toNfc().map { if (it.code < 0x20 || it.code == 0x7f || it in RESERVED) '_' else it }.joinToString("")
        .trim().trimStart('.').trimEnd('.', ' ')

private fun split(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || name.length - dot > MAX_EXTENSION_CHARS) return name to ""
    return name.substring(0, dot) to name.substring(dot)
}

private fun fit(stem: String, ext: String, fallback: String): String {
    val budget = (MAX_BYTES - ext.encodeToByteArray().size).coerceAtLeast(1)
    val bytes = stem.encodeToByteArray()
    val cut = if (bytes.size <= budget) stem else bytes.copyOf(budget).trimToUtf8Boundary().decodeToString().trimEnd('.', ' ')
    val safe = cut.ifEmpty { fallback }
    // Windows reserves a device name before any extension: con.txt and nul.tar.gz too.
    return (if (safe.substringBefore('.').trimEnd().uppercase() in WINDOWS_DEVICES) "_$safe" else safe) + ext
}
