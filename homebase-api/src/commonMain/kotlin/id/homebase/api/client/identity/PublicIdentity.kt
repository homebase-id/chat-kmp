package id.homebase.api.client.identity

import androidx.compose.runtime.Immutable
import id.homebase.api.common.OdinId

@Immutable
data class PublicIdentity(
    val odinId: OdinId,
    val displayName: String?,
    val firstName: String?,
    val surName: String?,
    val status: String?,
    /** Public short-bio summary (plain text), from the `short-bio-summary` sitedata section.
     *  Readable before connecting; null when the identity hasn't set one. */
    val shortBioSummary: String? = null,
)

fun PublicIdentity.initials(): String {
    val first = firstName?.trim()?.takeIf { it.isNotEmpty() }?.firstOrNull()
    val last = surName?.trim()?.takeIf { it.isNotEmpty() }?.firstOrNull()

    if (first != null && last != null) {
        return "${first}${last}".uppercase()
    }

    val tokens = displayName
        ?.trim()
        ?.split("\\s+".toRegex())
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    return when {
        tokens.size >= 2 -> "${tokens.first().first()}${tokens.last().first()}".uppercase()
        tokens.size == 1 -> tokens.first().first().uppercaseChar().toString()
        else -> odinId.domainName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }
}

fun PublicIdentity.displayNameOrDomain(): String =
    displayName?.takeIf { it.isNotBlank() } ?: odinId.domainName
