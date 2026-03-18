package id.homebase.chat.services.convo.contact

import kotlinx.serialization.Serializable

@Serializable
data class ContactName(
    val displayName: String?,
    val givenName: String?,
    val additionalName: String?,
    val surname: String?
) {

    fun initials(): String {
        val first =
            givenName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.firstOrNull()

        val last =
            surname
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.firstOrNull()

        if (first != null && last != null) {
            return "${first}${last}".uppercase()
        }

        // Fallback: try display name tokens
        if (displayName == null) {
            return "?"
        }

        val tokens =
            displayName
                .trim()
                .split("\\s+".toRegex())
                .filter { it.isNotEmpty() }

        return when {
            tokens.size >= 2 ->
                "${tokens.first().first()}${tokens.last().first()}".uppercase()

            tokens.size == 1 ->
                tokens.first().first().uppercaseChar().toString()

            else ->
                "?"
        }
    }
}