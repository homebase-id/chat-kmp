package id.homebase.core.scan

import id.homebase.api.image.readImageMetadata
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ScannedDocumentType { PaymentCard, IdCard, Card, Document }

private const val ID1_ASPECT_RATIO = 85.60 / 53.98
internal const val CARD_ASPECT_TOLERANCE = 0.12

private const val MIN_CARD_DIGITS = 13
private const val MAX_CARD_DIGITS = 19

private val digitRun = Regex("""\d(?:[ -]?\d){${MIN_CARD_DIGITS - 1},${MAX_CARD_DIGITS - 1}}""")
private val idKeywords = Regex(
    """\b(identity|id card|driver|driving licen[cs]e|passport|date of birth|dob|nationality)\b""",
    RegexOption.IGNORE_CASE,
)

internal fun isCardShaped(width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0) return false
    val ratio = max(width, height).toDouble() / min(width, height)
    return abs(ratio - ID1_ASPECT_RATIO) <= CARD_ASPECT_TOLERANCE
}

internal fun luhnValid(digits: String): Boolean {
    var sum = 0
    digits.reversed().forEachIndexed { i, c ->
        var d = c - '0'
        if (i % 2 == 1) {
            d *= 2
            if (d > 9) d -= 9
        }
        sum += d
    }
    return digits.isNotEmpty() && sum % 10 == 0
}

internal fun classifyCardText(text: String): ScannedDocumentType {
    if (idKeywords.containsMatchIn(text)) return ScannedDocumentType.IdCard
    val hasCardNumber = digitRun.findAll(text).any { match ->
        val digits = match.value.filter { it.isDigit() }
        digits.length in MIN_CARD_DIGITS..MAX_CARD_DIGITS && luhnValid(digits)
    }
    return if (hasCardNumber) ScannedDocumentType.PaymentCard else ScannedDocumentType.Card
}

/** A scan is a card only when every page is card-shaped; text recognition then runs in memory on those pages alone. */
suspend fun classifyScan(pages: List<ByteArray>): ScannedDocumentType {
    val allCards = pages.isNotEmpty() && pages.all { page ->
        readImageMetadata(page)?.let { isCardShaped(it.pixelWidth ?: 0, it.pixelHeight ?: 0) } == true
    }
    if (!allCards) return ScannedDocumentType.Document
    return classifyCardText(pages.mapNotNull { recognizeCardText(it) }.joinToString("\n"))
}
