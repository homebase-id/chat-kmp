package id.homebase.chat.contactcard

import id.homebase.core.util.initials

internal enum class ContactValueKind { Phone, Email }

internal data class ContactCardValue(val kind: ContactValueKind, val value: String)

internal data class ContactCardBubbleValues(
    val rows: List<ContactCardValue>,
    val hiddenCount: Int,
)

internal const val ContactCardBubbleRowLimit = 2

// A card authored by another client can carry a blank entry; it is not a row worth painting.
internal fun ContactCardDescriptor.renderablePhones(): List<String> = phones.filter { it.isNotBlank() }

internal fun ContactCardDescriptor.renderableEmails(): List<String> = emails.filter { it.isNotBlank() }

internal fun ContactCardDescriptor.allValues(): List<ContactCardValue> =
    renderablePhones().map { ContactCardValue(ContactValueKind.Phone, it) } +
        renderableEmails().map { ContactCardValue(ContactValueKind.Email, it) }

internal fun ContactCardDescriptor.hasTitleOfItsOwn(): Boolean =
    displayName.isNotBlank() || organization.isNotBlank()

internal fun ContactCardDescriptor.bubbleValues(
    limit: Int = ContactCardBubbleRowLimit,
): ContactCardBubbleValues {
    // A nameless card's first value is already the title (see summaryLine) — don't repeat it.
    val candidates = if (hasTitleOfItsOwn()) allValues() else allValues().drop(1)
    return ContactCardBubbleValues(
        rows = candidates.take(limit),
        hiddenCount = (candidates.size - limit).coerceAtLeast(0),
    )
}

internal fun ContactCardDescriptor.subtitleLine(): String =
    organization.takeIf { it.isNotBlank() && it != summaryLine() }.orEmpty()

// Blank for a card carrying only a phone/email — the caller falls back to an icon.
internal fun ContactCardDescriptor.avatarInitials(): String =
    displayName.ifBlank { organization }.initials()

// `Char.isDigit()` would let Arabic-Indic and Devanagari digits through, producing a `tel:` URI no
// dialer can parse.
internal fun String.dialable(): String = filter { it in '0'..'9' || it in "+*#," }

// RFC 6068: without percent-encoding, a `?` in the address opens the rest of it as mailto headers —
// subject, cc, even body.
internal fun String.mailtoTarget(): String {
    val utf8 = trim().encodeToByteArray()
    return buildString {
        for (byte in utf8) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in MAILTO_SAFE) {
                append(char)
            } else {
                append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
            }
        }
    }
}

private const val MAILTO_SAFE = "-._~!$'()*+,;:@"
private const val HEX = "0123456789ABCDEF"
