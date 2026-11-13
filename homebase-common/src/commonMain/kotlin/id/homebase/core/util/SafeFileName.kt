package id.homebase.core.util

import id.homebase.api.util.toNfc

private const val MAX_BYTES = 255
private const val MAX_EXTENSION_CHARS = 32
private const val RESERVED = "<>:\"/\\|?*"
private val WINDOWS_DEVICES = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

/** One path segment that every platform we save to takes (Windows is the strictest); [extension] is appended. */
fun safeFileName(raw: String?, fallback: String = "file", extension: String? = null): String =
    build(lastSegment(raw), fallback, extension)

/** A payload saved to the device: the sender's name, with an extension from [contentType] when it has none. */
fun saveFileName(name: String?, fallbackStem: String, contentType: String?): String {
    val segment = lastSegment(name)
    val extension = if (splitExtension(segment).second.isEmpty()) extensionForContentType(contentType) else null
    return build(segment, fallbackStem, extension)
}

/** Stem and extension (with its dot). A leading dot names a hidden file, and an overlong "extension" stays in the stem. */
fun splitExtension(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || name.length - dot > MAX_EXTENSION_CHARS) return name to ""
    return name.substring(0, dot) to name.substring(dot)
}

private fun build(segment: String, fallback: String, extension: String?): String {
    if (extension == null) return splitExtension(segment).let { (stem, ext) -> fit(stem, ext, fallback) }
    val ext = clean(extension)
    return fit(segment, if (ext.isEmpty()) "" else ".$ext", fallback)
}

private fun lastSegment(raw: String?): String = clean(raw.orEmpty().substringAfterLast('/').substringAfterLast('\\'))

private fun clean(name: String): String =
    buildString(name.length) {
        for (c in name.toNfc()) append(if (c.code < 0x20 || c.code == 0x7f || c in RESERVED) '_' else c)
    }.trim().trimStart('.').trimEnd('.', ' ')

// The cut comes from the stem so the extension survives.
private fun fit(stem: String, ext: String, fallback: String): String {
    val budget = (MAX_BYTES - ext.encodeToByteArray().size).coerceAtLeast(1)
    val bytes = stem.encodeToByteArray()
    val cut = if (bytes.size <= budget) stem else bytes.copyOf(budget).trimToUtf8Boundary().decodeToString().trimEnd('.', ' ')
    val safe = cut.ifEmpty { fallback }
    // Windows reserves a device name before any extension: con.txt and nul.tar.gz too.
    return (if (safe.substringBefore('.').trimEnd().uppercase() in WINDOWS_DEVICES) "_$safe" else safe) + ext
}
