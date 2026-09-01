package id.homebase.api.util

/**
 * The shape a mention's identity must have, matched immediately after the `@`. Byte-for-byte
 * the domain half of the web client's own receive-side test
 * (`dotyoucore-js` `ChatMessageItem.tsx`: `/(?:^|\s|[\r\n])@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/`).
 */
private val mentionIdentityShape = Regex("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

/**
 * Locates the `@mention`s in a raw chat body, as ranges into [text].
 *
 * A mention rides the wire as plain text — `@<odinId> `, nothing on the message header — so both
 * clients recognise one purely by shape. This is chat-kmp's half of that agreement, and it accepts
 * exactly what the web client accepts:
 *
 *  - the `@` must sit at the start of the body or directly after whitespace (an email address
 *    `alice@example.test` therefore never matches — its `@` follows a letter);
 *  - the token running from the `@` to the next whitespace must *begin* with a domain shape,
 *    [mentionIdentityShape]. Note that web's regex only has to match a PREFIX of the token, so
 *    `@alice.example.t` is a mention on both clients (via `alice.example`) even though its last
 *    label is too short to be a TLD.
 *
 * The returned range covers the `@` plus the token, with trailing non-alphanumerics trimmed off.
 * Web instead paints its link over the whole token, punctuation included — `@alice.example.test,`
 * links the comma and lands it in the href. Whether a token *is* a mention is identical between the
 * two; the decoration's reach differs only when a mention is followed, with no space, by characters
 * that cannot end an identity.
 *
 * Ranges are non-overlapping and ascending, and both ends land on a whole code point: the range
 * opens on the ASCII `@` and closes on a letter or digit, so an emoji shoved against a mention is
 * trimmed away entire rather than halved.
 *
 * Nothing here knows about markdown. Callers that render markdown must additionally refuse to
 * decorate the ranges that fall inside code — see the chat renderer's mention annotator.
 */
fun findMentionRanges(text: String): List<IntRange> {
    if (text.length < 2) return emptyList()

    var ranges: MutableList<IntRange>? = null
    var i = 0
    while (i < text.length) {
        if (text[i] != '@' || (i > 0 && !text[i - 1].isWhitespace())) {
            i++
            continue
        }
        var tokenEnd = i + 1
        while (tokenEnd < text.length && !text[tokenEnd].isWhitespace()) tokenEnd++

        val identity = mentionIdentityShape.matchAt(text, i + 1)
        if (identity != null) {
            // The identity match can stop short of the token (`@alice.example.t` matches only
            // `alice.example`), so decorate the whole token and walk back over trailing
            // punctuation instead — a chip that stops mid-identity reads as a bug. The match
            // always ends on a letter, so this can never eat into it.
            var end = tokenEnd
            while (end > identity.range.last + 1 && !text[end - 1].isLetterOrDigit()) end--
            val list = ranges ?: mutableListOf<IntRange>().also { ranges = it }
            list.add(i until end)
        }
        // Web consumes the whole whitespace-delimited token whether or not it turned out to be a
        // mention, so a second `@` inside the same token is never reconsidered.
        i = tokenEnd
    }
    return ranges ?: emptyList()
}
