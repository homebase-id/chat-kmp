package id.homebase.api.client.auth

import androidx.compose.runtime.Immutable
import id.homebase.api.common.OdinId

@Immutable
data class OwnerSession(
    val odinId: OdinId,
    val displayName: String?,
    val firstName: String?,
    val surName: String?,
    val profileImageFileId: String?,
    val profileImageFileKey: String?,
    val profileImagePreviewThumbnail: String?, // base64 content
    val profileImageLastModified: Long?,
    val status: String?
)

fun OwnerSession.initials(): String {
    val first =
        firstName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.firstOrNull()

    val last =
        surName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.firstOrNull()

    if (first != null && last != null) {
        return "${first}${last}".uppercase()
    }

    val tokens =
        displayName
            ?.trim()
            ?.split("\\s+".toRegex())
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    return when {
        tokens.size >= 2 ->
            "${tokens.first().first()}${tokens.last().first()}".uppercase()

        tokens.size == 1 ->
            tokens.first().first().uppercaseChar().toString()

        else ->
            "?"
    }
}