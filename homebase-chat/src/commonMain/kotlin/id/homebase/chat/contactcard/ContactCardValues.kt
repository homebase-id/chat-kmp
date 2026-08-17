package id.homebase.chat.contactcard

import id.homebase.core.util.initials

internal enum class ContactValueKind { Identity, Phone, Email }

internal data class ContactCardValue(val kind: ContactValueKind, val value: String)

internal data class ContactCardBubbleValues(
    val rows: List<ContactCardValue>,
    val hiddenCount: Int,
)

internal const val ContactCardBubbleRowLimit = 3

// A card authored by another client can carry a blank entry; it is not a row worth painting.
internal fun ContactCardDescriptor.renderablePhones(): List<String> =
    phones.map { it.scrubbed() }.filter { it.isNotBlank() }

internal fun ContactCardDescriptor.renderableEmails(): List<String> =
    emails.map { it.scrubbed() }.filter { it.isNotBlank() }

internal fun ContactCardDescriptor.renderableIdentity(): String? = identity()?.domainName

// Identity first: it is the only globally unique value here, so it is what a nameless card is
// titled by and the first thing worth showing under a name.
internal fun ContactCardDescriptor.allValues(): List<ContactCardValue> =
    listOfNotNull(renderableIdentity()?.let { ContactCardValue(ContactValueKind.Identity, it) }) +
        renderablePhones().map { ContactCardValue(ContactValueKind.Phone, it) } +
        renderableEmails().map { ContactCardValue(ContactValueKind.Email, it) }

internal fun ContactCardDescriptor.bubbleValues(
    limit: Int = ContactCardBubbleRowLimit,
): ContactCardBubbleValues {
    val all = allValues()
    // By value, at any position: displayName is arbitrary text that can equal any of these — a
    // connection has it resolved from the odinId, and a vCard can put the email in FN — and the
    // value it matches is not necessarily the first.
    val title = summaryLine()
    val candidates = all.filterNot { it.value.equals(title, ignoreCase = true) }
    return ContactCardBubbleValues(
        rows = candidates.take(limit),
        hiddenCount = (candidates.size - limit).coerceAtLeast(0),
    )
}

internal fun ContactCardDescriptor.subtitleLine(): String =
    organization.scrubbed()
        .takeIf { it.isNotBlank() && !it.equals(summaryLine(), ignoreCase = true) }
        .orEmpty()

// Blank for a card carrying only a phone/email/identity — the caller falls back to an icon.
internal fun ContactCardDescriptor.avatarInitials(): String =
    displayName
        .ifBlank { listOf(givenName, surname).filter { it.isNotBlank() }.joinToString(" ") }
        .ifBlank { organization }
        .initials()

// `Char.isDigit()` would let Arabic-Indic and Devanagari digits through, producing a `tel:` URI no
// dialer can parse.
internal fun String.dialable(): String = filter { it in '0'..'9' || it in "+*#," }

/**
 * MMI/USSD control codes (`*21*<number>#` sets up call forwarding) start with `*` or `#`, and a
 * phone number never does. The card is authored remotely, so a value of that shape is not offered
 * as callable — `#` mid-number is still a legitimate extension terminator and survives.
 */
internal fun String.isControlCode(): Boolean =
    // After the separators a dialer skips: ",*21*…#" would otherwise pass on its leading pause.
    dialable().dropWhile { it == ',' || it == '+' }.firstOrNull()
        ?.let { it == '*' || it == '#' } == true

// RFC 3966: an unescaped '#' is the fragment delimiter, so a dialer parses it as the end of the
// number. ',' stays — in tel: it is a legitimate DTMF pause.
internal fun String.telTarget(): String = dialable().replace("#", "%23")

// RFC 5724 makes ',' the recipient separator in sms:, so a card value carrying one would silently
// address a second number.
internal fun String.smsTarget(): String = dialable().filter { it != ',' }.replace("#", "%23")

// Shape only, and deliberately lenient: this exists to stop a mail client opening on a garbage
// recipient, not to validate addresses. :homebase-chat cannot see ContactFieldValidation.
internal fun String.looksLikeEmail(): Boolean {
    val v = trim()
    val at = v.indexOf('@')
    val dot = v.indexOf('.', at + 1)
    return at > 0 && at == v.lastIndexOf('@') && dot > at + 1 && dot < v.length - 1 &&
        v.none { it.isWhitespace() }
}

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

// ',' and ';' are deliberately absent: RFC 6068 makes ',' a recipient separator (and
// Outlook-family clients treat ';' the same), so leaving them addresses the compose window to
// whoever the card names after the real recipient.
private const val MAILTO_SAFE = "-._~!$'()*+:@"
private const val HEX = "0123456789ABCDEF"
