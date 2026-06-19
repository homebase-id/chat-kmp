package id.homebase.api.client.contacts

/**
 * Single source of truth for turning a contact's [ContactName] into the values UIs render, shared
 * by every consumer of [ContactRepository] so display-name/initials logic can't drift.
 *
 * The name is stored redundantly (a combined `displayName` plus `givenName`/`surname` parts), and
 * for connection/profile-synced contacts only `displayName` is set — these helpers encode the one
 * agreed resolution order.
 */

/**
 * Best display name: `displayName`, else `givenName surname`, else the identity/contact-point
 * fallbacks in [odinId] → [phone] → [email] order. Null only when nothing renderable exists.
 */
fun ContactName?.resolveDisplayName(
    odinId: String? = null,
    phone: String? = null,
    email: String? = null,
): String? {
    this?.displayName?.takeIf { it.isNotBlank() }?.let { return it }
    val composed = listOfNotNull(this?.givenName, this?.surname)
        .joinToString(" ")
        .trim()
    if (composed.isNotBlank()) return composed
    odinId?.takeIf { it.isNotBlank() }?.let { return it }
    phone?.takeIf { it.isNotBlank() }?.let { return it }
    email?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

/**
 * Avatar initials: first letters of given+surname when both present, else first letters of the
 * first/last whitespace tokens of `displayName`, else `"?"`.
 */
fun ContactName?.initials(): String {
    val first = this?.givenName?.trim()?.takeIf { it.isNotEmpty() }?.firstOrNull()
    val last = this?.surname?.trim()?.takeIf { it.isNotEmpty() }?.firstOrNull()
    if (first != null && last != null) return "$first$last".uppercase()

    val display = this?.displayName ?: return "?"
    val tokens = display.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
    return when {
        tokens.size >= 2 -> "${tokens.first().first()}${tokens.last().first()}".uppercase()
        tokens.size == 1 -> tokens.first().first().uppercaseChar().toString()
        else -> "?"
    }
}
