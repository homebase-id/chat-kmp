package id.homebase.api.client.link

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.encodeUrl
import id.homebase.api.util.sanitizePreviewText
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
        // Don't log the full response — link-preview bodies embed base64 image data-URIs that
        // can be 100KB+ each and bloat homebase.log. Log status + headers + body length only.
        Logger.d { "getLinkPreview: status=${response.status} headers=${response.headers} bodyLen=${response.body.length}" }

        if (response.status == 404) {
            return null
        }

        throwForFailure(response)

        return deserialize<LinkPreview>(response.body).sanitize()
    }

    companion object {
        private const val MAX_FIELD_LENGTH = 300

        private fun LinkPreview.sanitize(): LinkPreview {
            val cleanTitle = title.sanitizePreviewText().truncateToCodePoints(MAX_FIELD_LENGTH)
            val cleanDesc = description.sanitizePreviewText().truncateToCodePoints(MAX_FIELD_LENGTH)
            return copy(
                title = cleanTitle,
                description = if (cleanDesc.equals(cleanTitle, ignoreCase = true)) "" else cleanDesc,
            )
        }
    }
}