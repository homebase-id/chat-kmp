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

    fun isValidEmail(value: String): Boolean {
        val v = value.trim()
        return v.isEmpty() || EMAIL.matches(v)
    }

    fun isValidPhone(value: String): Boolean {
        val v = value.trim()
        return v.isEmpty() || E164.matches(v)
    }

    /** Validates against the real OdinId parser (the backend's source of truth). */
    fun isValidOdinId(value: String): Boolean {
        val v = value.trim()
        return v.isEmpty() || runCatching { OdinId(v) }.isSuccess
    }

    /** Strips spaces/dashes/parens from a phone so "+1 (415) 555-0123" → "+14155550123". */
    fun normalizePhone(value: String): String =
        value.trim().replace(Regex("[\\s()\\-.]"), "")
}
