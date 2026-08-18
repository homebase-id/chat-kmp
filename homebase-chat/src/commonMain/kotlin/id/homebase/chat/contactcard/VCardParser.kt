package id.homebase.chat.contactcard

import id.homebase.api.util.truncateToCodePoints

/**
 * One `VCARD` block reduced to the fields this app renders. Raw — values are exactly as the
 * card spelled them; normalization (E.164, email validity) happens in the importer that owns
 * `ContactFieldValidation`.
 */
data class VCardContact(
    val formattedName: String = "",
    val givenName: String = "",
    val surname: String = "",
    val organization: String = "",
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
) {
    val displayName: String
        get() = formattedName.ifBlank {
            listOf(givenName, surname).filter { it.isNotBlank() }.joinToString(" ")
        }

    val isEmpty: Boolean
        get() = displayName.isBlank() && phones.isEmpty() && emails.isEmpty() && organization.isBlank()
}

/**
 * Minimal vCard reader covering the properties a contact card renders: `FN`, `N`, `TEL`,
 * `EMAIL`, `ORG`. Handles the 2.1/3.0/4.0 basics that every exporter emits — line unfolding,
 * `GROUP.PROPERTY;PARAM=value:value` splitting, and `\,` `\;` `\n` `\\` value escapes.
 *
 * Deliberately NOT handled: `QUOTED-PRINTABLE` / `BASE64` encodings, `PHOTO`, `ADR`, `BDAY`,
 * `URL`, `NOTE`, and vCard 4.0 `PREF=` ordering. A quoted-printable value is surfaced raw
 * rather than decoded; add the decoder (and an `ADR`/`BDAY` mapping) when a card in the wild
 * actually needs it.
 */
object VCardParser {

    private const val MAX_VALUES_PER_KIND = 10
    private const val MAX_NAME_CODEPOINTS = 80
    private const val MAX_VALUE_CODEPOINTS = 120

    fun looksLikeVCard(text: String?): Boolean =
        text != null && text.contains("BEGIN:VCARD", ignoreCase = true)

    /**
     * Every `VCARD` block in [text], in document order. Empty when nothing parses — callers
     * fall back to sending the raw file rather than dropping the share.
     */
    fun parse(text: String): List<VCardContact> {
        val lines = unfold(text)
        val cards = mutableListOf<VCardContact>()
        var current: Builder? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.equals("BEGIN:VCARD", ignoreCase = true)) {
                current = Builder()
                continue
            }
            if (trimmed.equals("END:VCARD", ignoreCase = true)) {
                current?.build()?.let { cards.add(it) }
                current = null
                continue
            }
            current?.consume(trimmed)
        }
        // An unterminated block (truncated file) still yields whatever it carried.
        current?.build()?.let { cards.add(it) }
        return cards
    }

    fun parseFirst(text: String): VCardContact? = parse(text).firstOrNull()

    /**
     * Joins continuation lines (RFC 6350 folding: a line starting with SPACE or TAB continues
     * the previous one) and drops blank lines.
     */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        for (raw in text.split("\r\n", "\r", "\n")) {
            if (raw.isEmpty()) continue
            val isContinuation = raw[0] == ' ' || raw[0] == '\t'
            if (isContinuation && out.isNotEmpty()) {
                out.last().append(raw.substring(1))
            } else {
                out.add(StringBuilder(raw))
            }
        }
        return out.map { it.toString() }
    }

    private class Builder {
        var formattedName = ""
        var givenName = ""
        var surname = ""
        var organization = ""
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()

        fun consume(line: String) {
            val colon = line.indexOf(':')
            if (colon <= 0) return
            val head = line.substring(0, colon)
            val value = line.substring(colon + 1)
            // "item1.TEL;TYPE=CELL" -> name "TEL". The group prefix is presentation grouping
            // (Apple exports use it) and carries nothing we render.
            val name = head.substringBefore(';').substringAfterLast('.').trim().uppercase()
            when (name) {
                "FN" -> if (formattedName.isBlank()) formattedName = unescape(value).cap(MAX_NAME_CODEPOINTS)
                "N" -> {
                    val parts = splitEscaped(value, ';')
                    if (surname.isBlank()) surname = parts.getOrNull(0).orEmpty().unescapeAndCap(MAX_NAME_CODEPOINTS)
                    if (givenName.isBlank()) givenName = parts.getOrNull(1).orEmpty().unescapeAndCap(MAX_NAME_CODEPOINTS)
                }
                "ORG" -> if (organization.isBlank()) {
                    organization = splitEscaped(value, ';').firstOrNull().orEmpty()
                        .unescapeAndCap(MAX_NAME_CODEPOINTS)
                }
                "TEL" -> add(phones, unescape(value).cap(MAX_VALUE_CODEPOINTS))
                "EMAIL" -> add(emails, unescape(value).cap(MAX_VALUE_CODEPOINTS))
            }
        }

        private fun add(target: MutableList<String>, value: String) {
            val v = value.trim()
            if (v.isBlank() || v in target || target.size >= MAX_VALUES_PER_KIND) return
            target.add(v)
        }

        fun build(): VCardContact? = VCardContact(
            formattedName = formattedName.trim(),
            givenName = givenName.trim(),
            surname = surname.trim(),
            organization = organization.trim(),
            phones = phones.toList(),
            emails = emails.toList(),
        ).takeUnless { it.isEmpty }
    }

    /** Splits on [delim] occurrences that are not backslash-escaped. Escapes stay intact. */
    private fun splitEscaped(value: String, delim: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && i + 1 < value.length -> {
                    current.append(c).append(value[i + 1])
                    i += 2
                }
                c == delim -> {
                    parts.add(current.toString())
                    current.clear()
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        parts.add(current.toString())
        return parts
    }

    private fun unescape(value: String): String {
        if ('\\' !in value) return value.trim()
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString().trim()
    }

    private fun String.unescapeAndCap(max: Int): String = unescape(this).cap(max)

    /** Surrogate-safe cap — a name ending in an emoji must not lose half its pair. */
    private fun String.cap(max: Int): String = truncateToCodePoints(max)
}
