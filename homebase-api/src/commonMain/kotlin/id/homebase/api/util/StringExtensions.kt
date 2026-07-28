package id.homebase.api.util

private val htmlEntityPattern = Regex("&(#(\\d+)|#x([0-9a-fA-F]+)|(\\w+));")

private val namedEntities = mapOf(
    "amp" to '&', "lt" to '<', "gt" to '>', "quot" to '"', "apos" to '\'',
    "nbsp" to ' ', "ndash" to '–', "mdash" to '—',
    "lsquo" to '‘', "rsquo" to '’', "ldquo" to '“', "rdquo" to '”',
    "bull" to '•', "hellip" to '…', "copy" to '©', "reg" to '®',
    "trade" to '™', "euro" to '€', "pound" to '£', "yen" to '¥',
)

private fun codePointToString(cp: Int): String {
    return if (cp <= 0xFFFF) {
        Char(cp).toString()
    } else {
        val hi = Char(((cp - 0x10000) shr 10) + 0xD800)
        val lo = Char(((cp - 0x10000) and 0x3FF) + 0xDC00)
        "$hi$lo"
    }
}

fun String.decodeHtmlEntities(): String {
    if ('&' !in this) return this
    return htmlEntityPattern.replace(this) { match ->
        val decimal = match.groupValues[2]
        val hex = match.groupValues[3]
        val named = match.groupValues[4]
        when {
            decimal.isNotEmpty() -> {
                val cp = decimal.toIntOrNull()
                if (cp != null && cp in 1..0x10FFFF) codePointToString(cp)
                else match.value
            }
            hex.isNotEmpty() -> {
                val cp = hex.toIntOrNull(16)
                if (cp != null && cp in 1..0x10FFFF) codePointToString(cp)
                else match.value
            }
            named.isNotEmpty() -> namedEntities[named]?.toString() ?: match.value
            else -> match.value
        }
    }
}

private val whitespaceRun = Regex("\\s+")
private val quoteBetweenWords = Regex("(\\w)([\"“”])(\\w)")

fun String.sanitizePreviewText(): String =
    decodeHtmlEntities()
        .replace(quoteBetweenWords, "$1 $2$3")
        .replace(whitespaceRun, " ")
        .trim()

private const val NOTE_PREVIEW_MAX_LENGTH = 200

/**
 * One-line plain-text preview of a markdown body, capped at
 * [NOTE_PREVIEW_MAX_LENGTH] code points, or null if the body has no visible
 * text. Delegates to the shared [markdownToPlainPreview] AST walk so the
 * grammar mirrors the renderer exactly (the old regex `[*_~`>]` class stripped
 * legitimate hyphens/underscores from the middle of plain words).
 */
fun String.stripMarkdownForPreview(): String? {
    val plain = markdownToPlainPreview(this, NOTE_PREVIEW_MAX_LENGTH)
    if (plain.isEmpty()) return null
    return plain
}

// Truncate a string to maxVisibleCharacters (be sure UTF characters aren't chopped in the middle)
fun String.truncateToCodePoints(maxVisibleCharacters: Int): String {
    if (maxVisibleCharacters <= 0) return ""
    var codePointCount = 0
    var charIndex = 0
    while (charIndex < length && codePointCount < maxVisibleCharacters) {
        if (charIndex + 1 < length && this[charIndex].isHighSurrogate() && this[charIndex + 1].isLowSurrogate()) {
            charIndex += 2
        } else {
            charIndex += 1
        }
        codePointCount += 1
    }
    return substring(0, charIndex)
}

/**
 * Counts Unicode code points (not UTF-16 chars), so a surrogate pair (emoji or
 * other non-BMP character) counts as one. Mirrors [truncateToCodePoints]'s walk —
 * use it for length-budget checks on user text instead of `.length`.
 */
fun String.codePointCount(): Int {
    var count = 0
    var charIndex = 0
    while (charIndex < length) {
        charIndex += if (charIndex + 1 < length && this[charIndex].isHighSurrogate() && this[charIndex + 1].isLowSurrogate()) 2 else 1
        count += 1
    }
    return count
}

/**
 * Cleans a domain string. Also built to handle a full string like a pasted URL, removing invalid
 * characters, and enforcing domain rules. Supports Unicode characters for IDNs (to be
 * Punycode-converted later) and handles common user input typos. It's intended to be called with
 * each character being input interactively (or pasted).
 * @param preserveTrailingDot If true, allows a single trailing dot (for interactive typing). If
 *   false, removes trailing dots (for final validation).
 * @param preserveTrailingDash If true, keeps a single trailing dash on the label being typed (so a
 *   dash you just pressed isn't eaten mid-type). If false, strips it (for final validation). Mirrors
 *   [preserveTrailingDot].
 * @return The cleaned domain string in lowercase.
 */
fun String.cleanDomain(
    preserveTrailingDot: Boolean = true,
    preserveTrailingDash: Boolean = true,
): String {
    if (this.isEmpty()) {
        return ""
    }

    // Remember if input ended with a dot or space (before we clean it)
    // Spaces will be converted to dots, so treat trailing space as trailing dot
    val hadTrailingDotOrSpace = this.endsWith(".") || this.endsWith(" ") || this.endsWith(",")

    // Start with trimming only leading whitespace (preserve trailing for now)
    var cleanedString = this.trimStart()

    if (cleanedString.isEmpty()) {
        return ""
    }

    // Step 1: Handle pasted URLs - Strip protocols (http/https:// or similar), paths (after /),
    // and
    // queries (after ?)
    cleanedString =
        cleanedString
            // Normalize common protocol typos
            .replace(
                Regex("/{2,}"),
                "//"
            ) // Collapses multiple consecutive slashes (2+) to //
            .replace(Regex(":/((?!/))"), "://") // Fix :/ to :// (missing one slash)
            .replace(
                Regex("^([\\w+-]+)(//)"),
                "$1:$2"
            ) // Fix scheme// to scheme:// (missing colon; no . in scheme)
            // Remove general protocol (scheme:// where scheme can be any word-like
            // string,
            // but no . to avoid domain mismatches)
            .replace(Regex("^[\\w+-]+://", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\?.*$"), "") // Remove query params after ?
            .replace(Regex("#.*$"), "") // Remove fragments after # (handle URL anchors)
            .replace(Regex("/.*$"), "") // Remove paths after /

    // Step 2: Replace spaces and commas with periods
    cleanedString = cleanedString.replace(" ", ".").replace(",", ".")

    // Step 3: Remove illegal characters (e.g., #, ?, /, \, &, %, @, !, *, (, ), [, ], {, }, :,
    // ;,
    // ', ", <, >, =, +, ~, `, |, $, ^ )
    // but allow Unicode letters and digits (for later Punycode conversion)
    // Note: spaces are already handled in Step 2, so we don't include them here
    cleanedString = cleanedString.replace(Regex("[#?/\\\\&%@!*()\\[\\]{}:;'\",<>=~`|$^]"), "")

    // Step 4: Replace multiple consecutive periods with a single period
    cleanedString = cleanedString.replace(Regex("\\.{2,}"), ".")

    // Step 5: Enforce per-label rules (no start/end with '-', no consecutive '-'). A single
    // trailing dash on the last label (the one being typed) is preserved when preserveTrailingDash
    // is true, so a just-pressed '-' survives as-you-type — mirrors the trailing-dot handling below.
    val labels = cleanedString.split(".")
    val lastIndex = labels.lastIndex
    val cleanedLabels =
        labels.mapIndexed { index, label ->
            // Always strip a leading '-' and collapse consecutive '-'.
            var l = label.replace(Regex("^-+"), "").replace(Regex("-{2,}"), "-")
            // Strip a trailing '-' except on the label being typed (last) when preserving.
            if (!(preserveTrailingDash && index == lastIndex)) {
                l = l.replace(Regex("-+$"), "")
            }
            l
        }
    cleanedString =
        cleanedLabels.filter { it.isNotEmpty() }.joinToString(".") // Remove empty labels

    // Step 6: Remove leading periods. Only remove trailing periods if preserveTrailingDot is
    // false
    cleanedString = cleanedString.replace(Regex("^\\.+"), "") // Remove leading periods
    if (!preserveTrailingDot) {
        cleanedString = cleanedString.replace(Regex("\\.+$"), "") // Remove trailing periods
    }

    // Step 7: Ensure lowercase (domains are case-insensitive)
    cleanedString = cleanedString.lowercase().trim()

    // Restore single trailing dot if it was present (as dot, space, or comma) and we're
    // preserving it
    if (preserveTrailingDot &&
        hadTrailingDotOrSpace &&
        !cleanedString.endsWith(".") &&
        cleanedString.isNotEmpty()
    ) {
        cleanedString += "."
    }

    return cleanedString
}

