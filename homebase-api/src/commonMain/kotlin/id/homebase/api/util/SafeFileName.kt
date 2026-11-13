package id.homebase.api.util

private const val MAX_BYTES = 255
private const val MAX_EXTENSION_CHARS = 32
private const val RESERVED = "<>:\"/\\|?*"
private val WINDOWS_DEVICES = setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") +
    listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "¹", "²", "³").flatMap { listOf("COM$it", "LPT$it") }

/** One path segment that every platform we save to takes (Windows is the strictest); [extension] is appended. */
fun safeFileName(raw: String?, fallback: String = "file", extension: String? = null): String =
    build(lastSegment(raw), fallback, extension)

/** [name] if it's free, else "stem (1).ext", "stem (2).ext"… as Android, Windows and browsers number a copy: a save never replaces a file unasked. */
fun freeFileName(name: String, isTaken: (String) -> Boolean): String {
    if (!isTaken(name)) return name
    val (stem, ext) = splitExtension(name)
    return generateSequence(1) { it + 1 }.map { fit(stem, " ($it)$ext", "file") }.first { !isTaken(it) }
}

/** Stem and extension (with its dot). A leading dot names a hidden file, and an overlong "extension" stays in the stem. */
fun splitExtension(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || name.length - dot > MAX_EXTENSION_CHARS) return name to ""
    return name.substring(0, dot) to name.substring(dot)
}

private fun build(segment: String, fallback: String, extension: String?): String {
    if (extension == null) return splitExtension(segment).let { (stem, ext) -> fit(stem, ext, fallback) }
    val ext = clean(extension).take(MAX_EXTENSION_CHARS - 1)
    return fit(segment, if (ext.isEmpty()) "" else ".$ext", fallback)
}

private fun lastSegment(raw: String?): String = clean(raw.orEmpty().substringAfterLast('/').substringAfterLast('\\'))

private fun clean(name: String): String =
    buildString(name.length) {
        for (c in name.toNfc()) append(if (c.code < 0x20 || c.code == 0x7f || c in RESERVED) '_' else c)
    }.trim().trimStart('.').trimEnd('.', ' ')

// The cut comes from the stem so the extension survives.
private fun fit(stem: String, ext: String, fallback: String): String {
    val whole = stem.ifEmpty { fallback }
    // Windows reserves a device name before any extension: con.txt and nul.tar.gz too.
    val prefix = if (whole.substringBefore('.').trimEnd().uppercase() in WINDOWS_DEVICES) "_" else ""
    val budget = MAX_BYTES - ext.encodeToByteArray().size - prefix.length
    val bytes = whole.encodeToByteArray()
    val cut = if (bytes.size <= budget) whole else bytes.copyOf(budget).trimToUtf8Boundary().decodeToString().trimEnd('.', ' ')
    return prefix + cut.ifEmpty { fallback } + ext
}
