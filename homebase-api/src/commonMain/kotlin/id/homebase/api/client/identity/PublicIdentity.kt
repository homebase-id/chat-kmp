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

fun PublicIdentity.displayNameOrDomain(): String =
    displayName?.takeIf { it.isNotBlank() } ?: odinId.domainName
