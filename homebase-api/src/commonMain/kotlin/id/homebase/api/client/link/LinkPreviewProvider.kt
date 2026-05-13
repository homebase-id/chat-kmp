package id.homebase.api.client.link

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.encodeUrl
import id.homebase.api.util.decodeHtmlEntities
import id.homebase.api.util.truncateToCodePoints
import io.ktor.client.HttpClient

class LinkPreviewProvider(
    httpClient: HttpClient, credentialsManager: CredentialsManager
) : OdinApiProviderBase(httpClient, credentialsManager) {

    suspend fun getLinkPreview(url: String): LinkPreview? {

        val standardisedUrl = if (url.startsWith("http")) url else "https://${url}"

        val creds = requireCreds()
        val url = apiUrl(
            creds.domain, "/links/extract"
        )

        val response = encryptedGet(
            url = url,
            token = creds.accessToken,
            secret = creds.secret,
            queryString = "url=${standardisedUrl}"
        )
        Logger.d("getLinkPreview: $response")

        if (response.status == 404) {
            return null
        }

        throwForFailure(response)

        return deserialize<LinkPreview>(response.body)?.sanitize()
    }

    companion object {
        private const val MAX_FIELD_LENGTH = 300

        private val whitespaceRun = Regex("\\s+")

        private fun String.collapseWhitespace(): String =
            whitespaceRun.replace(this, " ")

        private fun LinkPreview.sanitize(): LinkPreview {
            val cleanTitle = title.decodeHtmlEntities().collapseWhitespace().trim()
                .truncateToCodePoints(MAX_FIELD_LENGTH)
            val cleanDesc = description.decodeHtmlEntities().collapseWhitespace().trim()
                .truncateToCodePoints(MAX_FIELD_LENGTH)
            return copy(
                title = cleanTitle,
                description = if (cleanDesc.equals(cleanTitle, ignoreCase = true)) "" else cleanDesc,
            )
        }
    }
}