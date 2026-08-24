package id.homebase.core.ui.screens.contactbook

import id.homebase.api.common.OdinId

/**
 * Lightweight validators for contact fields. All treat blank as valid (fields are
 * optional); callers gate the empty-vs-required decision separately. Validation is
 * format-only and intentionally lenient.
 */
object ContactFieldValidation {

    // Local part + domain + TLD; no spaces. Not RFC-exhaustive, but rejects the
    // common mistakes (missing @, missing dot, stray spaces).
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    // E.164: leading '+', first digit 1-9, then up to 14 more digits (max 15 total).
    private val E164 = Regex("^\\+[1-9]\\d{1,14}$")

    // ISO-8601 calendar date, zero-padded: 1969-02-22. Shape only — the calendar
    // check (month range, days-in-month, leap year) happens in isValidBirthday.
    private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    fun isValidEmail(value: String): Boolean {
        val v = value.trim()
        return v.isEmpty() || EMAIL.matches(v)
    }

    fun isValidPhone(value: String): Boolean {
        val v = value.trim()
        return v.isEmpty() || E164.matches(v)
    }

    /**
     * A birthday must be a real ISO-8601 calendar date (`YYYY-MM-DD`) — the shape callers
     * store and every other client parses. Rejects both malformed shapes (`1969-02-222`,
     * `2/22/69`) and impossible dates (`1969-13-01`, `2023-02-29`). Format-only: a date in
     * the future is accepted, since validating that needs a clock and the whole object is
     * deliberately clock-free.
     */
    fun isValidBirthday(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return true
        val m = ISO_DATE.matchEntire(v) ?: return false
        val (year, month, day) = m.destructured.toList().map { it.toInt() }
        if (year < 1 || month !in 1..12) return false
        return day in 1..daysInMonth(year, month)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (isLeapYear(year)) 29 else 28
    }

    private fun isLeapYear(year: Int): Boolean =
        year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    /** Validates against the real OdinId parser (the backend's source of truth). */
    fun isValidOdinId(value: String): Boolean {
        val v = value.trim()
        return v.isEmpty() || runCatching { OdinId(v) }.isSuccess
    }

    /** Strips spaces/dashes/parens from a phone so "+1 (415) 555-0123" → "+14155550123". */
    fun normalizePhone(value: String): String =
        value.trim().replace(Regex("[\\s()\\-.]"), "")
}
