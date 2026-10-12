package id.homebase.core.ui.screens.filesync

import id.homebase.api.util.toNfc
import id.homebase.core.util.trimToUtf8Boundary

/** Filesync's name rules (filesync docs/spec/node-schema-v1.md): NFC, at most 255 UTF-8 bytes, no / or NUL, not . or .. */
internal object FilesyncNames {
    private const val MAX_BYTES = 255
    private const val FALLBACK = "untitled"

    /** Two names collide in a folder when their keys match: case and Unicode form are ignored, as on macOS and Windows. */
    fun key(name: String): String = name.toNfc().lowercase()

    /** [requested] made valid, then numbered "(1)", "(2)"… until no key in [takenKeys] uses it. */
    fun freeName(requested: String, takenKeys: Set<String>): String {
        val (stem, extension) = split(clean(requested))
        var candidate = fit(stem, "", extension)
        var n = 1
        while (key(candidate) in takenKeys) {
            candidate = fit(stem, " ($n)", extension)
            n++
        }
        return candidate
    }

    /** Whether a name read from a node keeps the rules; NFC isn't checked, as another client's name is shown as it is. */
    fun isValid(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name

    private fun clean(name: String): String =
        name.toNfc().replace('/', '_').replace('\u0000', '_').takeIf(::isValid) ?: FALLBACK

    fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        // A leading dot is a hidden file's name, not an extension; an oversized "extension" is kept whole in the stem.
        if (dot <= 0 || name.length - dot > 32) return name to ""
        return name.substring(0, dot) to name.substring(dot)
    }

    // Only the stem is cut: cutting the " (n)" suffix could turn a numbered name back into the taken one.
    private fun fit(stem: String, suffix: String, extension: String): String {
        val budget = (MAX_BYTES - (suffix + extension).encodeToByteArray().size).coerceAtLeast(0)
        val stemBytes = stem.encodeToByteArray()
        val trimmed = if (stemBytes.size <= budget) stem else stemBytes.copyOf(budget).trimToUtf8Boundary().decodeToString()
        return trimmed.ifEmpty { FALLBACK } + suffix + extension
    }
}
